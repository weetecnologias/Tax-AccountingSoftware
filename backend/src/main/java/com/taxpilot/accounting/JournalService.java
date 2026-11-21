package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.accounting.AccountingDtos.JournalDetailDto;
import com.taxpilot.accounting.AccountingDtos.JournalLineDto;
import com.taxpilot.accounting.AccountingDtos.JournalLineRequest;
import com.taxpilot.accounting.AccountingDtos.JournalRequest;
import com.taxpilot.accounting.AccountingDtos.JournalSummaryDto;
import com.taxpilot.accounting.AccountingRepositories.JournalRepository;
import com.taxpilot.accounting.AccountingRepositories.LedgerAccountRepository;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.Client;
import com.taxpilot.clients.ClientService;
import com.taxpilot.clients.ClientStatus;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.PageResponse;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.settings.SettingsService;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

/**
 * Journal lifecycle: DRAFT → PENDING_APPROVAL → APPROVED → POSTED (or CANCELLED; rejection returns to DRAFT).
 * The preparer can never approve or post their own entry. Posted entries are immutable (also enforced by DB
 * triggers); corrections are made with a reversal entry that goes through the same approval path.
 */
@Service
public class JournalService {

    public static final String WRITE = "ACCOUNTING_WRITE";
    public static final String APPROVE = "ACCOUNTING_APPROVE";

    private final JournalRepository journals;
    private final LedgerAccountRepository accounts;
    private final PeriodService periods;
    private final ClientService clients;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public JournalService(JournalRepository journals, LedgerAccountRepository accounts, PeriodService periods,
                          ClientService clients, UserRepository users, AuditService audit, JdbcTemplate jdbc,
                          SettingsService settings) {
        this.journals = journals;
        this.accounts = accounts;
        this.periods = periods;
        this.clients = clients;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Result of a create call: whether the entry was newly created or returned for a repeated idempotency key. */
    public record CreateResult(JournalDetailDto journal, boolean created) {
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PageResponse<JournalSummaryDto> list(UUID clientId, JournalEntry.Status status, LocalDate from,
                                                LocalDate to, String q, Pageable pageable) {
        clients.find(clientId);
        Specification<JournalEntry> spec = (r, query, cb) -> cb.equal(r.get("clientId"), clientId);
        if (status != null) spec = spec.and((r, query, cb) -> cb.equal(r.get("status"), status));
        if (from != null) spec = spec.and((r, query, cb) -> cb.greaterThanOrEqualTo(r.get("entryDate"), from));
        if (to != null) spec = spec.and((r, query, cb) -> cb.lessThanOrEqualTo(r.get("entryDate"), to));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            spec = spec.and((r, query, cb) -> cb.or(cb.like(cb.lower(r.get("narration")), like),
                    cb.like(cb.lower(r.get("reference")), like), cb.like(cb.lower(r.get("entryNumber")), like)));
        }
        Map<UUID, UserRef> userCache = new HashMap<>();
        return PageResponse.of(journals.findAll(spec, pageable), j -> new JournalSummaryDto(j.getId(),
                j.getEntryNumber(), j.getEntryDate(), j.getEntryType(), j.getStatus(), j.getNarration(),
                j.getReference(), Money.str(j.getTotalDebit()), Money.str(j.getTotalCredit()), j.isBalanced(),
                userRef(j.getPreparedBy(), userCache), j.getCreatedAt(), j.getReversalOfId(), j.getReversedById()));
    }

    @Transactional(readOnly = true)
    public JournalDetailDto get(UUID id) {
        return toDetail(find(id));
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public CreateResult create(JournalRequest req, String idempotencyKey) {
        CurrentUser me = CurrentUser.require();
        String key = normaliseKey(idempotencyKey);
        if (key != null) {
            var existing = journals.findByClientIdAndIdempotencyKey(req.clientId(), key);
            if (existing.isPresent()) {
                return new CreateResult(toDetail(existing.get()), false);
            }
        }
        requireActiveClient(req.clientId());
        JournalEntry.Type type = req.entryType() == null ? JournalEntry.Type.GENERAL : req.entryType();
        if (type == JournalEntry.Type.REVERSAL || type == JournalEntry.Type.CLOSING) {
            throw ApiException.field("entryType", "Reversal and year-end closing entries are created by their own actions");
        }
        JournalEntry entry = new JournalEntry(req.clientId(), me.id(), type, null, key);
        apply(entry, req);
        journals.save(entry);
        audit.record("JOURNAL_CREATED", "JOURNAL_ENTRY", entry.getId(), entry.getClientId(), summary(entry));
        return new CreateResult(toDetail(entry), true);
    }

    @Transactional
    public JournalDetailDto update(UUID id, JournalRequest req) {
        JournalEntry entry = find(id);
        if (!entry.getClientId().equals(req.clientId())) {
            throw ApiException.notFound("Journal entry");
        }
        requireStatus(entry, JournalEntry.Status.DRAFT, "Only draft journals can be edited");
        if (entry.getEntryType() == JournalEntry.Type.REVERSAL || entry.getEntryType() == JournalEntry.Type.CLOSING) {
            throw ApiException.conflict("SYSTEM_ENTRY_LOCKED", "Reversal and closing entries are generated and cannot be edited");
        }
        if (req.entryType() == JournalEntry.Type.REVERSAL || req.entryType() == JournalEntry.Type.CLOSING) {
            throw ApiException.field("entryType", "This entry type cannot be chosen manually");
        }
        if (req.version() == null || req.version() != entry.getVersion()) {
            throw ApiException.conflict("STALE_VERSION", "This journal was changed by someone else. Reload and try again.");
        }
        requireActiveClient(entry.getClientId());
        apply(entry, req);
        journals.save(entry);
        audit.record("JOURNAL_UPDATED", "JOURNAL_ENTRY", id, entry.getClientId(), summary(entry));
        return toDetail(entry);
    }

    @Transactional
    public JournalDetailDto submit(UUID id) {
        CurrentUser me = CurrentUser.require();
        JournalEntry entry = find(id);
        requireStatus(entry, JournalEntry.Status.DRAFT, "Only draft journals can be submitted");
        requireActiveClient(entry.getClientId());
        if (!entry.isBalanced()) {
            throw ApiException.conflict("UNBALANCED", "Total debits (" + Money.str(entry.getTotalDebit())
                    + ") must equal total credits (" + Money.str(entry.getTotalCredit()) + ") before submitting");
        }
        requireOpenPeriod(entry);
        entry.submit(me.id());
        journals.save(entry);
        audit.record("JOURNAL_SUBMITTED", "JOURNAL_ENTRY", id, entry.getClientId(), summary(entry));
        return toDetail(entry);
    }

    @Transactional
    public JournalDetailDto approve(UUID id, String comment) {
        CurrentUser me = CurrentUser.require();
        JournalEntry entry = find(id);
        requireStatus(entry, JournalEntry.Status.PENDING_APPROVAL, "Only journals awaiting approval can be approved");
        boolean self = requireChecker(me, entry, "approve");
        entry.approve(me.id(), blank(comment));
        journals.save(entry);
        Map<String, Object> d = summary(entry);
        if (self) d.put("selfApproved", true);
        if (blank(comment) != null) d.put("comment", comment.trim());
        audit.record("JOURNAL_APPROVED", "JOURNAL_ENTRY", id, entry.getClientId(), d);
        return toDetail(entry);
    }

    @Transactional
    public JournalDetailDto reject(UUID id, String reason) {
        CurrentUser me = CurrentUser.require();
        JournalEntry entry = find(id);
        if (entry.getStatus() != JournalEntry.Status.PENDING_APPROVAL && entry.getStatus() != JournalEntry.Status.APPROVED) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only journals awaiting approval or posting can be rejected");
        }
        if (blank(reason) == null) {
            throw ApiException.field("reason", "Give a reason for rejecting the journal");
        }
        entry.reject(me.id(), reason.trim());
        journals.save(entry);
        Map<String, Object> d = summary(entry);
        d.put("reason", reason.trim());
        audit.record("JOURNAL_REJECTED", "JOURNAL_ENTRY", id, entry.getClientId(), d);
        return toDetail(entry);
    }

    @Transactional
    public JournalDetailDto post(UUID id) {
        CurrentUser me = CurrentUser.require();
        JournalEntry entry = find(id);
        if (entry.getStatus() == JournalEntry.Status.POSTED) {
            return toDetail(entry); // idempotent: a repeated post is a no-op
        }
        requireStatus(entry, JournalEntry.Status.APPROVED, "Only approved journals can be posted");
        boolean self = requireChecker(me, entry, "post");
        requireActiveClient(entry.getClientId());
        AccountingPeriod period = requireOpenPeriod(entry);
        if (!entry.isBalanced()) {
            throw ApiException.conflict("UNBALANCED", "The journal is not balanced");
        }
        for (JournalEntry.Line l : entry.getLines()) {
            LedgerAccount a = accounts.findById(l.getAccountId()).orElseThrow();
            if (!a.isActive() || a.isGroup()) {
                throw ApiException.conflict("ACCOUNT_UNAVAILABLE", "Account " + a.getCode() + " is inactive or a group");
            }
        }
        boolean closing = entry.getEntryType() == JournalEntry.Type.CLOSING;
        if (closing && !sameLines(entry.getLines(), closingLines(entry.getClientId(), entry.getClosingFinancialYear()))) {
            throw ApiException.conflict("STALE_CLOSING", "Income or expense balances changed after this closing entry was "
                    + "prepared. Cancel it and prepare the year-end close again.");
        }
        entry.post(me.id(), nextNumber(entry.getClientId(), period.getFinancialYear()));
        journals.saveAndFlush(entry);
        if (closing) {
            int closed = periods.closeYear(entry.getClientId(), entry.getClosingFinancialYear(), me.id());
            audit.record("FINANCIAL_YEAR_CLOSED", "CLIENT", entry.getClientId(), entry.getClientId(),
                    Map.of("financialYear", entry.getClosingFinancialYear(), "periodsClosed", closed,
                            "closingEntry", entry.getEntryNumber()));
        }
        if (entry.getReversalOfId() != null) {
            JournalEntry original = find(entry.getReversalOfId());
            original.markReversedBy(entry.getId());
            journals.save(original);
        }
        Map<String, Object> posted = summary(entry);
        if (self) posted.put("selfApproved", true);
        audit.record("JOURNAL_POSTED", "JOURNAL_ENTRY", id, entry.getClientId(), posted);
        return toDetail(entry);
    }

    @Transactional
    public JournalDetailDto cancel(UUID id) {
        JournalEntry entry = find(id);
        if (entry.getStatus() == JournalEntry.Status.POSTED || entry.getStatus() == JournalEntry.Status.CANCELLED) {
            throw ApiException.conflict("INVALID_TRANSITION", "Posted or cancelled journals cannot be cancelled");
        }
        entry.cancel();
        journals.save(entry);
        audit.record("JOURNAL_CANCELLED", "JOURNAL_ENTRY", id, entry.getClientId(), summary(entry));
        return toDetail(entry);
    }

    /** Creates a draft reversal (debits and credits swapped) that must itself be approved and posted. */
    @Transactional
    public JournalDetailDto reverse(UUID id, LocalDate date, String reason) {
        CurrentUser me = CurrentUser.require();
        JournalEntry original = find(id);
        requireStatus(original, JournalEntry.Status.POSTED, "Only posted journals can be reversed");
        if (original.getEntryType() == JournalEntry.Type.REVERSAL || original.getEntryType() == JournalEntry.Type.CLOSING) {
            throw ApiException.conflict("CANNOT_REVERSE", "Reversal and year-end closing entries cannot be reversed");
        }
        if (original.getReversedById() != null
                || journals.existsByReversalOfIdAndStatusNot(id, JournalEntry.Status.CANCELLED)) {
            throw ApiException.conflict("ALREADY_REVERSED", "This journal already has a reversal");
        }
        if (date.isBefore(original.getEntryDate())) {
            throw ApiException.field("reversalDate", "The reversal date cannot be before the original entry date");
        }
        requireActiveClient(original.getClientId());
        AccountingPeriod period = periods.periodFor(original.getClientId(), date, "reversalDate");
        if (period.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.field("reversalDate", "The period for this date is closed");
        }
        JournalEntry rev = new JournalEntry(original.getClientId(), me.id(), JournalEntry.Type.REVERSAL, id, null);
        rev.setEntryDate(date, period.getId());
        rev.setNarration(truncate("Reversal of " + original.getEntryNumber() + ": " + reason.trim(), 1000));
        rev.setReference(original.getEntryNumber());
        List<JournalEntry.Line> lines = new ArrayList<>();
        for (JournalEntry.Line l : original.getLines()) {
            lines.add(new JournalEntry.Line(l.getAccountId(), l.getCredit(), l.getDebit(), l.getDescription(), l.getPartyId()));
        }
        rev.replaceLines(lines);
        journals.save(rev);
        Map<String, Object> d = summary(rev);
        d.put("reversalOf", original.getEntryNumber());
        d.put("reason", reason.trim());
        audit.record("JOURNAL_REVERSAL_CREATED", "JOURNAL_ENTRY", rev.getId(), rev.getClientId(), d);
        return toDetail(rev);
    }

    // ---------------------------------------------------------------- year-end close

    /**
     * Prepares the year-end closing entry for a financial year as a DRAFT: every income and expense ledger's balance
     * for the year is reversed and the net result goes to retained earnings. It then follows the normal approval path;
     * posting it closes all periods of the year.
     */
    @Transactional
    public JournalDetailDto prepareYearEnd(UUID clientId, String financialYear) {
        CurrentUser me = CurrentUser.require();
        requireActiveClient(clientId);
        List<AccountingPeriod> year = periods.yearPeriods(clientId, financialYear);
        if (year.isEmpty()) throw ApiException.field("financialYear", "Financial year " + financialYear + " is not open");
        if (jdbc.queryForObject("""
                select count(*) from journal_entries where client_id = ? and closing_financial_year = ? and status <> 'CANCELLED'
                """, Integer.class, clientId, financialYear) > 0) {
            throw ApiException.conflict("ALREADY_CLOSING", "A closing entry for " + financialYear + " already exists");
        }
        LocalDate start = year.getFirst().getStartDate();
        LocalDate end = year.getLast().getEndDate();
        Integer unposted = jdbc.queryForObject("""
                select count(*) from journal_entries where client_id = ? and entry_date between ? and ?
                and status in ('PENDING_APPROVAL','APPROVED')
                """, Integer.class, clientId, start, end);
        if (unposted > 0) {
            throw ApiException.conflict("UNPOSTED_JOURNALS", unposted + " journal(s) in " + financialYear
                    + " await approval or posting. Resolve them before closing the year.");
        }
        AccountingPeriod last = year.getLast();
        if (last.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.conflict("PERIOD_CLOSED", "Reopen the last period of the year (" + last.getStartDate().getMonth()
                    + ") so the closing entry can be dated on " + end);
        }
        List<JournalEntry.Line> lines = closingLines(clientId, financialYear);
        if (lines.size() < 2) {
            throw ApiException.conflict("NOTHING_TO_CLOSE", "There are no income or expense balances to close for " + financialYear);
        }
        JournalEntry entry = new JournalEntry(clientId, me.id(), JournalEntry.Type.CLOSING, null, null);
        entry.setClosingFinancialYear(financialYear);
        entry.setEntryDate(end, last.getId());
        entry.setNarration("Year-end closing " + financialYear + ": transfer of profit or loss to retained earnings");
        entry.setReference("CLOSE " + financialYear);
        entry.replaceLines(lines);
        journals.save(entry);
        audit.record("YEAR_END_PREPARED", "JOURNAL_ENTRY", entry.getId(), clientId,
                Map.of("financialYear", financialYear, "lines", lines.size()));
        return toDetail(entry);
    }

    /** Lines that zero every P&L ledger for the year (excluding earlier closing entries) against retained earnings. */
    List<JournalEntry.Line> closingLines(UUID clientId, String financialYear) {
        List<AccountingPeriod> year = periods.yearPeriods(clientId, financialYear);
        if (year.isEmpty()) return List.of();
        LedgerAccount retained = accounts.findByClientIdAndTemplateCode(clientId, "IN-STANDARD:3200")
                .or(() -> accounts.findByClientIdOrderByCodeAsc(clientId).stream()
                        .filter(a -> "RETAINED_EARNINGS".equals(a.getSubtype()) && !a.isGroup()).findFirst())
                .orElseThrow(() -> ApiException.conflict("ACCOUNT_MISSING",
                        "No retained earnings ledger (subtype RETAINED_EARNINGS) exists for this client"));
        List<JournalEntry.Line> lines = new ArrayList<>();
        BigDecimal net = Money.ZERO; // positive = credit balance (profit)
        for (Map<String, Object> r : jdbc.queryForList("""
                select a.id, coalesce(sum(l.debit),0) as dr, coalesce(sum(l.credit),0) as cr
                from ledger_accounts a
                join journal_entry_lines l on l.account_id = a.id
                join journal_entries e on e.id = l.journal_entry_id
                where a.client_id = ? and a.account_type in ('INCOME','EXPENSE') and e.status = 'POSTED'
                  and e.entry_type <> 'CLOSING' and e.entry_date between ? and ?
                group by a.id, a.code order by a.code
                """, clientId, year.getFirst().getStartDate(), year.getLast().getEndDate())) {
            BigDecimal bal = Money.of((BigDecimal) r.get("dr")).subtract(Money.of((BigDecimal) r.get("cr")));
            if (bal.signum() == 0) continue;
            UUID acc = (UUID) r.get("id");
            lines.add(bal.signum() > 0 ? new JournalEntry.Line(acc, Money.ZERO, bal, "Close to retained earnings")
                    : new JournalEntry.Line(acc, bal.negate(), Money.ZERO, "Close to retained earnings"));
            net = net.subtract(bal);
        }
        if (net.signum() != 0) {
            lines.add(net.signum() > 0 ? new JournalEntry.Line(retained.getId(), Money.ZERO, net, "Profit for " + financialYear)
                    : new JournalEntry.Line(retained.getId(), net.negate(), Money.ZERO, "Loss for " + financialYear));
        }
        return lines;
    }

    private static boolean sameLines(List<JournalEntry.Line> a, List<JournalEntry.Line> b) {
        Map<String, BigDecimal> x = new HashMap<>();
        Map<String, BigDecimal> y = new HashMap<>();
        a.forEach(l -> x.merge(l.getAccountId().toString(), l.getDebit().subtract(l.getCredit()), BigDecimal::add));
        b.forEach(l -> y.merge(l.getAccountId().toString(), l.getDebit().subtract(l.getCredit()), BigDecimal::add));
        x.values().removeIf(v -> v.signum() == 0);
        y.values().removeIf(v -> v.signum() == 0);
        if (!x.keySet().equals(y.keySet())) return false;
        return x.entrySet().stream().allMatch(en -> en.getValue().compareTo(y.get(en.getKey())) == 0);
    }

    /**
     * Records an already-approved source document (e.g. an invoice approved under maker-checker) as a posted
     * journal in one transaction. The database triggers still enforce balance, open period and account rules.
     */
    @Transactional
    public JournalEntry createPosted(UUID clientId, LocalDate date, String narration, String reference,
                                     List<JournalEntry.Line> lines, UUID preparedBy, UUID submittedBy,
                                     UUID approvedBy, String sourceAction) {
        requireActiveClient(clientId);
        AccountingPeriod period = periods.periodFor(clientId, date, "invoiceDate");
        if (period.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.conflict("PERIOD_CLOSED", "The accounting period for " + date + " is closed");
        }
        for (JournalEntry.Line l : lines) {
            LedgerAccount a = accounts.findById(l.getAccountId())
                    .filter(acc -> acc.getClientId().equals(clientId))
                    .orElseThrow(() -> ApiException.badRequest("ACCOUNT_MISSING", "A required ledger account is missing"));
            if (a.isGroup() || !a.isActive()) {
                throw ApiException.conflict("ACCOUNT_UNAVAILABLE", "Account " + a.getCode() + " is inactive or a group");
            }
        }
        JournalEntry entry = new JournalEntry(clientId, preparedBy, JournalEntry.Type.GENERAL, null, null);
        entry.setEntryDate(date, period.getId());
        entry.setNarration(truncate(narration, 1000));
        entry.setReference(reference);
        entry.replaceLines(lines);
        if (!entry.isBalanced()) {
            throw new IllegalStateException("System-generated journal is not balanced");
        }
        entry.submit(submittedBy);
        entry.approve(approvedBy, null);
        journals.saveAndFlush(entry);
        entry.post(approvedBy, nextNumber(clientId, period.getFinancialYear()));
        journals.saveAndFlush(entry);
        Map<String, Object> d = summary(entry);
        d.put("source", sourceAction);
        audit.record("JOURNAL_POSTED", "JOURNAL_ENTRY", entry.getId(), clientId, d);
        return entry;
    }

    // ---------------------------------------------------------------- helpers

    private void apply(JournalEntry entry, JournalRequest req) {
        AccountingPeriod period = periods.periodFor(req.clientId(), req.entryDate(), "entryDate");
        if (period.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.field("entryDate", "The accounting period for this date is closed");
        }
        if (req.lines().size() < 2) {
            throw ApiException.field("lines", "A journal needs at least two lines");
        }
        List<JournalEntry.Line> lines = new ArrayList<>();
        for (int i = 0; i < req.lines().size(); i++) {
            JournalLineRequest lr = req.lines().get(i);
            final int lineNo = i + 1;
            String prefix = "lines[" + i + "]";
            BigDecimal dr = Money.parse(lr.debit(), prefix + ".debit");
            BigDecimal cr = Money.parse(lr.credit(), prefix + ".credit");
            if ((dr.signum() == 0) == (cr.signum() == 0)) {
                throw ApiException.field(prefix + ".debit", "Line " + (i + 1) + ": enter either a debit or a credit amount");
            }
            LedgerAccount a = accounts.findById(lr.accountId())
                    .filter(acc -> acc.getClientId().equals(req.clientId()))
                    .orElseThrow(() -> ApiException.field(prefix + ".accountId", "Line " + lineNo + ": account not found"));
            if (a.isGroup()) {
                throw ApiException.field(prefix + ".accountId", "Line " + (i + 1) + ": " + a.getCode()
                        + " is a group; choose a ledger account");
            }
            if (!a.isActive()) {
                throw ApiException.field(prefix + ".accountId", "Line " + (i + 1) + ": " + a.getCode() + " is inactive");
            }
            UUID partyId = lr.partyId();
            if (partyId != null) {
                if (!"RECEIVABLE".equals(a.getSubtype()) && !"PAYABLE".equals(a.getSubtype())) {
                    throw ApiException.field(prefix + ".partyId", "Line " + lineNo
                            + ": only receivable or payable lines can carry a customer or vendor");
                }
                Integer found = jdbc.queryForObject("select count(*) from parties where id = ? and client_id = ?",
                        Integer.class, partyId, req.clientId());
                if (found == null || found == 0) {
                    throw ApiException.field(prefix + ".partyId", "Line " + lineNo + ": customer or vendor not found");
                }
            }
            lines.add(new JournalEntry.Line(a.getId(), dr, cr, blank(lr.description()), partyId));
        }
        entry.setEntryDate(req.entryDate(), period.getId());
        entry.setNarration(req.narration().trim());
        entry.setReference(blank(req.reference()));
        entry.replaceLines(lines);
    }

    private String nextNumber(UUID clientId, String financialYear) {
        jdbc.update("""
                insert into journal_sequences (client_id, financial_year, next_value) values (?, ?, 1)
                on conflict (client_id, financial_year) do nothing
                """, clientId, financialYear);
        Integer n = jdbc.queryForObject("""
                update journal_sequences set next_value = next_value + 1
                where client_id = ? and financial_year = ? returning next_value - 1
                """, Integer.class, clientId, financialYear);
        return "JV/" + financialYear + "/" + String.format("%05d", n);
    }

    /** Returns true when the action is a self-approval permitted by the organisation's policy. */
    private boolean requireChecker(CurrentUser me, JournalEntry entry, String verb) {
        if (!me.has(APPROVE)) {
            throw ApiException.forbidden("FORBIDDEN", "Only an approver can " + verb + " journals");
        }
        return settings.checkApprover(me.id(), verb, entry.getPreparedBy(), entry.getSubmittedBy()).selfApproval();
    }

    private void requireActiveClient(UUID clientId) {
        Client c = clients.find(clientId);
        if (c.getStatus() != ClientStatus.ACTIVE) {
            throw ApiException.conflict("CLIENT_NOT_ACTIVE",
                    "Journals can only be recorded for active clients (this client is " + c.getStatus() + ")");
        }
    }

    private AccountingPeriod requireOpenPeriod(JournalEntry entry) {
        AccountingPeriod p = periods.find(entry.getPeriodId());
        if (p.getStatus() != AccountingPeriod.Status.OPEN) {
            throw ApiException.conflict("PERIOD_CLOSED", "The accounting period " + p.getStartDate().getMonth()
                    + " " + p.getStartDate().getYear() + " is closed");
        }
        return p;
    }

    private static void requireStatus(JournalEntry e, JournalEntry.Status expected, String message) {
        if (e.getStatus() != expected) {
            throw ApiException.conflict("INVALID_TRANSITION", message + " (current status: " + e.getStatus() + ")");
        }
    }

    private JournalEntry find(UUID id) {
        return journals.findById(id).orElseThrow(() -> ApiException.notFound("Journal entry"));
    }

    private Map<String, Object> summary(JournalEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e.getEntryNumber() != null) d.put("number", e.getEntryNumber());
        d.put("date", e.getEntryDate().toString());
        d.put("status", e.getStatus().name());
        d.put("totalDebit", Money.str(e.getTotalDebit()));
        d.put("totalCredit", Money.str(e.getTotalCredit()));
        d.put("lines", e.getLines().size());
        return d;
    }

    private UserRef userRef(UUID id, Map<UUID, UserRef> cache) {
        if (id == null) return null;
        return cache.computeIfAbsent(id, k -> UserRef.of(users.findById(k).orElse(null)));
    }

    private JournalDetailDto toDetail(JournalEntry e) {
        Map<UUID, UserRef> cache = new HashMap<>();
        Map<UUID, LedgerAccount> acc = new HashMap<>();
        List<JournalLineDto> lines = e.getLines().stream().map(l -> {
            LedgerAccount a = acc.computeIfAbsent(l.getAccountId(), k -> accounts.findById(k).orElse(null));
            String partyName = l.getPartyId() == null ? null : jdbc.queryForList(
                    "select name from parties where id = ?", String.class, l.getPartyId()).stream().findFirst().orElse(null);
            return new JournalLineDto(l.getId(), l.getLineNo(), l.getAccountId(), a == null ? null : a.getCode(),
                    a == null ? null : a.getName(), Money.str(l.getDebit()), Money.str(l.getCredit()), l.getDescription(),
                    l.getPartyId(), partyName);
        }).toList();
        AccountingPeriod period = periods.find(e.getPeriodId());
        String reversalOfNumber = e.getReversalOfId() == null ? null
                : journals.findById(e.getReversalOfId()).map(JournalEntry::getEntryNumber).orElse(null);
        String reversedByNumber = e.getReversedById() == null ? null
                : journals.findById(e.getReversedById()).map(JournalEntry::getEntryNumber).orElse(null);
        return new JournalDetailDto(e.getId(), e.getClientId(), e.getEntryNumber(), e.getEntryDate(), e.getEntryType(),
                e.getStatus(), e.getNarration(), e.getReference(), Money.str(e.getTotalDebit()),
                Money.str(e.getTotalCredit()), e.isBalanced(), period.getFinancialYear(), period.getStatus(),
                userRef(e.getPreparedBy(), cache), e.getCreatedAt(), userRef(e.getSubmittedBy(), cache),
                e.getSubmittedAt(), userRef(e.getApprovedBy(), cache), e.getApprovedAt(), e.getApprovalComment(),
                userRef(e.getPostedBy(), cache), e.getPostedAt(), userRef(e.getRejectedBy(), cache), e.getRejectedAt(),
                e.getRejectionReason(), e.getReversalOfId(), reversalOfNumber, e.getReversedById(), reversedByNumber,
                lines, allowedActions(e), e.getVersion());
    }

    /** Actions the current user may take, so the UI never shows a button the server would refuse. */
    private List<String> allowedActions(JournalEntry e) {
        CurrentUser me = CurrentUser.orNull();
        List<String> actions = new ArrayList<>();
        if (me == null) return actions;
        boolean maker = me.id().equals(e.getPreparedBy()) || me.id().equals(e.getSubmittedBy());
        boolean checker = me.has(APPROVE) && (!maker || settings.allowSelfApproval());
        switch (e.getStatus()) {
            case DRAFT -> {
                if (me.has(WRITE)) {
                    if (e.getEntryType() != JournalEntry.Type.REVERSAL && e.getEntryType() != JournalEntry.Type.CLOSING) {
                        actions.add("EDIT");
                    }
                    actions.add("SUBMIT");
                    actions.add("CANCEL");
                }
            }
            case PENDING_APPROVAL -> {
                if (checker) {
                    actions.add("APPROVE");
                    actions.add("REJECT");
                }
                if (me.has(WRITE)) actions.add("CANCEL");
            }
            case APPROVED -> {
                if (checker) {
                    actions.add("POST");
                    actions.add("REJECT");
                }
                if (me.has(WRITE)) actions.add("CANCEL");
            }
            case POSTED -> {
                if (me.has(WRITE) && e.getEntryType() != JournalEntry.Type.REVERSAL
                        && e.getEntryType() != JournalEntry.Type.CLOSING && e.getReversedById() == null) {
                    actions.add("REVERSE");
                }
            }
            default -> {
            }
        }
        return actions;
    }

    private static String normaliseKey(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim();
        if (!k.matches("^[A-Za-z0-9_-]{8,80}$")) {
            throw ApiException.badRequest("BAD_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-80 letters, digits, _ or -");
        }
        return k;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
