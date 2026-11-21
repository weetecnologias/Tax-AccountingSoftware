package com.taxpilot.banking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.taxpilot.accounting.AccountType;
import com.taxpilot.accounting.AccountingDtos.JournalLineRequest;
import com.taxpilot.accounting.AccountingDtos.JournalRequest;
import com.taxpilot.accounting.AccountingRepositories.LedgerAccountRepository;
import com.taxpilot.accounting.JournalService;
import com.taxpilot.accounting.LedgerAccount;
import com.taxpilot.accounting.Money;
import com.taxpilot.audit.AuditService;
import com.taxpilot.banking.BankEntities.ImportRepository;
import com.taxpilot.banking.BankEntities.StatementImport;
import com.taxpilot.banking.BankEntities.Transaction;
import com.taxpilot.banking.BankEntities.TransactionRepository;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;
import com.taxpilot.common.crypto.BlindIndex;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.settings.SettingsService;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

/**
 * Bank reconciliation. Statement rows are matched one-to-one against POSTED journal lines on the bank ledger.
 * Only EXACT matches (same amount and side, a unique candidate within 3 days that also shares a reference or
 * the same date) are applied automatically; everything else is a suggestion that a person confirms.
 * Nothing is posted automatically: an unmatched bank line can only become a DRAFT journal for approval.
 */
@Service
public class ReconciliationService {

    static final int EXACT_DAYS = 3;
    static final int SUGGEST_DAYS = 10;
    static final BigDecimal PARTIAL_TOLERANCE = new BigDecimal("1.00");

    // ---------------------------------------------------------------- DTOs

    public record ImportDto(UUID id, UUID ledgerAccountId, String ledgerAccountName, String bankFormat,
                            String originalFilename, LocalDate periodFrom, LocalDate periodTo, String openingBalance,
                            String closingBalance, String totalDeposits, String totalWithdrawals, int lineCount,
                            int duplicateCount, long matched, long unmatched, long excluded,
                            StatementImport.Status status, UserRef importedBy, Instant importedAt,
                            UserRef approvedBy, Instant approvedAt, String approvalComment, boolean selfApproved,
                            boolean canApprove, List<String> warnings) {
    }

    public record TransactionDto(UUID id, int rowNo, LocalDate txnDate, LocalDate valueDate, String description,
                                 String reference, String withdrawal, String deposit, String balance,
                                 String categoryHint, Transaction.Status status, String matchType,
                                 Integer matchConfidence, MatchedLine matchedLine, String exclusionReason) {
    }

    public record MatchedLine(UUID lineId, UUID journalId, String entryNumber, LocalDate entryDate, String narration,
                              String amount) {
    }

    public record Suggestion(UUID lineId, UUID journalId, String entryNumber, LocalDate entryDate, String narration,
                             String reference, String amount, String matchType, int confidence, List<String> evidence) {
    }

    public record Summary(UUID importId, LocalDate asOf, String balancePerBank, String balancePerBooks,
                          String bankOnlyDeposits, String bankOnlyWithdrawals, String bookOnlyDebits,
                          String bookOnlyCredits, String adjustedBookBalance, String difference, boolean reconciled,
                          List<MatchedLine> bookOnlyEntries) {
    }

    public record ImportResult(ImportDto statement, int autoMatched) {
    }

    // ---------------------------------------------------------------- wiring

    private final ImportRepository imports;
    private final TransactionRepository txns;
    private final LedgerAccountRepository accounts;
    private final ClientService clients;
    private final JournalService journals;
    private final SettingsService settings;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public ReconciliationService(ImportRepository imports, TransactionRepository txns, LedgerAccountRepository accounts,
                                 ClientService clients, JournalService journals, SettingsService settings,
                                 UserRepository users, AuditService audit, JdbcTemplate jdbc) {
        this.imports = imports;
        this.txns = txns;
        this.accounts = accounts;
        this.clients = clients;
        this.journals = journals;
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- import

    @Transactional
    public ImportResult importStatement(UUID clientId, UUID ledgerAccountId, MultipartFile file) {
        CurrentUser me = CurrentUser.require();
        clients.findOpen(clientId);
        LedgerAccount bank = accounts.findById(ledgerAccountId).filter(a -> a.getClientId().equals(clientId))
                .orElseThrow(() -> ApiException.field("ledgerAccountId", "Bank ledger not found"));
        if (bank.isGroup() || bank.getAccountType() != AccountType.ASSET) {
            throw ApiException.field("ledgerAccountId", "Choose the bank ledger account (an asset ledger)");
        }
        if (file == null || file.isEmpty()) throw ApiException.field("file", "Choose a statement file");
        if (file.getSize() > 10L * 1024 * 1024) throw ApiException.field("file", "Statements are limited to 10 MB");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String sha = BlindIndex.sha256Hex(data);
        if (imports.findByLedgerAccountIdAndSha256(ledgerAccountId, sha).isPresent()) {
            throw ApiException.conflict("DUPLICATE_STATEMENT", "This exact statement file was already imported for this bank account");
        }
        String filename = com.taxpilot.documents.FileTypeDetector.sanitiseFilename(file.getOriginalFilename());
        BankStatementParser.Result parsed = BankStatementParser.parse(filename, data);

        StatementImport imp = new StatementImport(clientId, ledgerAccountId, parsed.format().name(), filename, sha, me.id());
        BigDecimal deposits = Money.ZERO;
        BigDecimal withdrawals = Money.ZERO;
        List<Transaction> fresh = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int duplicates = 0;
        for (BankStatementParser.Row r : parsed.rows()) {
            String base = r.date() + "|" + r.valueDate() + "|" + r.description().toUpperCase(Locale.ROOT) + "|"
                    + r.reference() + "|" + r.withdrawal() + "|" + r.deposit() + "|" + r.balance();
            int n = occurrences.merge(base, 1, Integer::sum);
            String fp = BlindIndex.sha256Hex(base + "#" + n);
            if (txns.existsByLedgerAccountIdAndFingerprint(ledgerAccountId, fp)) {
                duplicates++;
                continue;
            }
            fresh.add(new Transaction(imp.getId(), clientId, ledgerAccountId, r, fp, categoryHint(r.description())));
            deposits = deposits.add(r.deposit());
            withdrawals = withdrawals.add(r.withdrawal());
        }
        if (fresh.isEmpty()) {
            throw ApiException.conflict("NOTHING_NEW", "Every transaction in this file was already imported");
        }
        imp.setSummary(parsed.rows().getFirst().date(), parsed.rows().getLast().date(), parsed.openingBalance(),
                parsed.closingBalance(), deposits, withdrawals, fresh.size(), duplicates);
        imp = imports.saveAndFlush(imp);
        txns.saveAllAndFlush(fresh);
        int auto = autoMatch(imp, me.id());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("file", filename);
        d.put("format", parsed.format().name());
        d.put("transactions", fresh.size());
        d.put("duplicatesSkipped", duplicates);
        d.put("autoMatched", auto);
        audit.record("BANK_STATEMENT_IMPORTED", "BANK_STATEMENT", imp.getId(), clientId, d);
        return new ImportResult(toDto(imp, parsed.warnings()), auto);
    }

    // ---------------------------------------------------------------- matching

    @Transactional
    public int rerunAutoMatch(UUID importId) {
        StatementImport imp = findImport(importId);
        requireInProgress(imp);
        int n = autoMatch(imp, CurrentUser.require().id());
        audit.record("BANK_AUTO_MATCH_RUN", "BANK_STATEMENT", importId, imp.getClientId(), Map.of("matched", n));
        return n;
    }

    private int autoMatch(StatementImport imp, UUID by) {
        List<Candidate> pool = candidates(imp.getLedgerAccountId(), imp.getPeriodFrom().minusDays(SUGGEST_DAYS),
                imp.getPeriodTo().plusDays(SUGGEST_DAYS));
        Set<UUID> taken = new HashSet<>(txns.matchedLineIds(imp.getLedgerAccountId()));
        int matched = 0;
        for (Transaction t : txns.findByImportIdOrderByRowNoAsc(imp.getId())) {
            if (t.getStatus() != Transaction.Status.UNMATCHED) continue;
            List<Candidate> exact = pool.stream().filter(c -> !taken.contains(c.lineId))
                    .filter(c -> c.amount.compareTo(t.amount()) == 0 && c.debit == t.isDeposit())
                    .filter(c -> Math.abs(ChronoUnit.DAYS.between(c.date, t.getTxnDate())) <= EXACT_DAYS)
                    .filter(c -> c.date.equals(t.getTxnDate()) || referenceMatch(t, c))
                    .toList();
            if (exact.size() == 1) {
                Candidate c = exact.getFirst();
                t.match(c.lineId, "EXACT", referenceMatch(t, c) ? 100 : 95, by);
                taken.add(c.lineId);
                txns.save(t);
                matched++;
            }
        }
        return matched;
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID txnId) {
        Transaction t = findTxn(txnId);
        Set<UUID> taken = new HashSet<>(txns.matchedLineIds(t.getLedgerAccountId()));
        List<Suggestion> out = new ArrayList<>();
        for (Candidate c : candidates(t.getLedgerAccountId(), t.getTxnDate().minusDays(SUGGEST_DAYS),
                t.getTxnDate().plusDays(SUGGEST_DAYS))) {
            if (taken.contains(c.lineId) || c.debit != t.isDeposit()) continue;
            long days = Math.abs(ChronoUnit.DAYS.between(c.date, t.getTxnDate()));
            BigDecimal diff = c.amount.subtract(t.amount()).abs();
            boolean ref = referenceMatch(t, c);
            List<String> evidence = new ArrayList<>();
            String type;
            int score;
            if (diff.signum() == 0) {
                type = "PROBABLE";
                score = (int) Math.max(50, 90 - 5 * days) + (ref ? 10 : 0);
                evidence.add("Same amount ₹" + Money.str(c.amount));
            } else if (diff.compareTo(PARTIAL_TOLERANCE) <= 0) {
                type = "PARTIAL";
                score = 40 + (ref ? 10 : 0);
                evidence.add("Amount differs by ₹" + Money.str(diff));
            } else {
                continue;
            }
            evidence.add(days == 0 ? "Same date" : days + " day(s) apart");
            if (ref) evidence.add("Reference appears in both");
            out.add(new Suggestion(c.lineId, c.journalId, c.entryNumber, c.date, c.narration, c.reference,
                    Money.str(c.amount), type, Math.min(score, 99), evidence));
        }
        out.sort(Comparator.comparingInt(Suggestion::confidence).reversed());
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    @Transactional
    public TransactionDto match(UUID txnId, UUID lineId) {
        CurrentUser me = CurrentUser.require();
        Transaction t = findTxn(txnId);
        requireInProgress(findImport(t.getImportId()));
        if (t.getStatus() != Transaction.Status.UNMATCHED) throw ApiException.conflict("ALREADY_RESOLVED", "This bank line is already matched or excluded");
        Candidate c = candidates(t.getLedgerAccountId(), LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)).stream()
                .filter(x -> x.lineId.equals(lineId)).findFirst()
                .orElseThrow(() -> ApiException.field("lineId", "That ledger entry is not a posted line on this bank account"));
        if (c.debit != t.isDeposit()) {
            throw ApiException.field("lineId", "Direction differs: a bank deposit must match a debit to the bank ledger and a withdrawal a credit");
        }
        if (txns.findByMatchedLineId(lineId).isPresent()) throw ApiException.conflict("LINE_TAKEN", "That ledger entry is already reconciled");
        BigDecimal diff = c.amount.subtract(t.amount()).abs();
        if (diff.compareTo(PARTIAL_TOLERANCE) > 0) {
            throw ApiException.field("lineId", "Amounts differ by ₹" + Money.str(diff) + "; record the difference with a journal first");
        }
        t.match(lineId, diff.signum() == 0 ? "MANUAL" : "MANUAL_PARTIAL", diff.signum() == 0 ? 100 : 60, me.id());
        txns.save(t);
        audit.record("BANK_TXN_MATCHED", "BANK_TRANSACTION", txnId, t.getClientId(),
                Map.of("entry", c.entryNumber, "amount", Money.str(t.amount()), "difference", Money.str(diff)));
        return toDto(t);
    }

    @Transactional
    public TransactionDto unmatch(UUID txnId) {
        Transaction t = findTxn(txnId);
        requireInProgress(findImport(t.getImportId()));
        if (t.getStatus() == Transaction.Status.UNMATCHED) return toDto(t);
        String previous = t.getStatus().name();
        t.unmatch();
        txns.save(t);
        audit.record("BANK_TXN_UNMATCHED", "BANK_TRANSACTION", txnId, t.getClientId(), Map.of("previous", previous));
        return toDto(t);
    }

    @Transactional
    public TransactionDto exclude(UUID txnId, String reason) {
        Transaction t = findTxn(txnId);
        requireInProgress(findImport(t.getImportId()));
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "Explain why this line is excluded");
        if (t.getStatus() == Transaction.Status.MATCHED) throw ApiException.conflict("ALREADY_RESOLVED", "Unmatch the line first");
        t.exclude(reason.trim(), CurrentUser.require().id());
        txns.save(t);
        audit.record("BANK_TXN_EXCLUDED", "BANK_TRANSACTION", txnId, t.getClientId(), Map.of("reason", reason.trim()));
        return toDto(t);
    }

    /** Creates a DRAFT journal for an unmatched bank line (e.g. bank charges). It must be approved and posted. */
    @Transactional
    public UUID createJournal(UUID txnId, UUID counterAccountId, String narration) {
        Transaction t = findTxn(txnId);
        requireInProgress(findImport(t.getImportId()));
        if (t.getStatus() != Transaction.Status.UNMATCHED) throw ApiException.conflict("ALREADY_RESOLVED", "This bank line is already resolved");
        String amount = Money.str(t.amount());
        JournalLineRequest bankLine = new JournalLineRequest(t.getLedgerAccountId(), t.isDeposit() ? amount : null,
                t.isDeposit() ? null : amount, "As per bank statement");
        JournalLineRequest counter = new JournalLineRequest(counterAccountId, t.isDeposit() ? null : amount,
                t.isDeposit() ? amount : null, null);
        String text = narration == null || narration.isBlank() ? t.getDescription() : narration.trim();
        JournalRequest req = new JournalRequest(t.getClientId(), t.getTxnDate(), null,
                text.length() > 1000 ? text.substring(0, 1000) : text, t.getReference(), List.of(bankLine, counter), null);
        UUID journalId = journals.create(req, "bank-" + txnId).journal().id();
        audit.record("BANK_TXN_JOURNAL_DRAFTED", "BANK_TRANSACTION", txnId, t.getClientId(),
                Map.of("journalId", journalId.toString(), "amount", amount));
        return journalId;
    }

    // ---------------------------------------------------------------- sign-off and summary

    @Transactional
    public ImportDto approve(UUID importId, String comment) {
        CurrentUser me = CurrentUser.require();
        StatementImport imp = findImport(importId);
        requireInProgress(imp);
        if (!me.has(JournalService.APPROVE)) throw ApiException.forbidden("FORBIDDEN", "Only an approver can sign off a reconciliation");
        long open = txns.countByImportIdAndStatus(importId, Transaction.Status.UNMATCHED);
        if (open > 0) {
            throw ApiException.conflict("UNMATCHED_LINES", open + " bank line(s) are still unmatched. Match, record or exclude them first.");
        }
        boolean self = settings.checkApprover(me.id(), "sign off", imp.getImportedBy()).selfApproval();
        Summary s = summary(importId);
        imp.reconcile(me.id(), self, comment == null || comment.isBlank() ? null : comment.trim());
        imports.save(imp);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("balancePerBank", s.balancePerBank());
        d.put("balancePerBooks", s.balancePerBooks());
        d.put("difference", s.difference());
        if (self) d.put("selfApproved", true);
        audit.record("BANK_RECONCILIATION_APPROVED", "BANK_STATEMENT", importId, imp.getClientId(), d);
        return toDto(imp, List.of());
    }

    /**
     * Bank reconciliation statement at the statement end date:
     * books + deposits only in bank − withdrawals only in bank − debits only in books + credits only in books
     * should equal the bank's closing balance.
     */
    @Transactional(readOnly = true)
    public Summary summary(UUID importId) {
        StatementImport imp = findImport(importId);
        LocalDate asOf = imp.getPeriodTo();
        BigDecimal books = Money.of(jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0) from journal_entry_lines l
                join journal_entries e on e.id = l.journal_entry_id
                where l.account_id = ? and e.status = 'POSTED' and e.entry_date <= ?
                """, BigDecimal.class, imp.getLedgerAccountId(), asOf));
        BigDecimal bankDep = Money.ZERO;
        BigDecimal bankWd = Money.ZERO;
        for (Transaction t : txns.findByImportIdOrderByRowNoAsc(importId)) {
            // Excluded lines are acknowledged reconciling items: in the bank but deliberately not in the books.
            if (t.getStatus() != Transaction.Status.MATCHED) {
                bankDep = bankDep.add(t.getDeposit());
                bankWd = bankWd.add(t.getWithdrawal());
            }
        }
        Set<UUID> matched = new HashSet<>(txns.matchedLineIds(imp.getLedgerAccountId()));
        BigDecimal bookDr = Money.ZERO;
        BigDecimal bookCr = Money.ZERO;
        List<MatchedLine> bookOnly = new ArrayList<>();
        for (Candidate c : candidates(imp.getLedgerAccountId(), imp.getPeriodFrom(), asOf)) {
            if (matched.contains(c.lineId)) continue;
            if (c.debit) bookDr = bookDr.add(c.amount);
            else bookCr = bookCr.add(c.amount);
            bookOnly.add(new MatchedLine(c.lineId, c.journalId, c.entryNumber, c.date, c.narration,
                    (c.debit ? "" : "-") + Money.str(c.amount)));
        }
        BigDecimal adjusted = books.add(bankDep).subtract(bankWd).subtract(bookDr).add(bookCr);
        BigDecimal bank = imp.getClosingBalance();
        BigDecimal diff = bank == null ? null : bank.subtract(adjusted);
        return new Summary(importId, asOf, bank == null ? null : Money.str(bank), Money.str(books), Money.str(bankDep),
                Money.str(bankWd), Money.str(bookDr), Money.str(bookCr), Money.str(adjusted),
                diff == null ? null : Money.str(diff), diff != null && diff.signum() == 0, bookOnly);
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<ImportDto> list(UUID clientId) {
        clients.find(clientId);
        return imports.findByClientIdOrderByImportedAtDesc(clientId).stream().map(i -> toDto(i, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public ImportDto get(UUID importId) {
        return toDto(findImport(importId), List.of());
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> transactions(UUID importId) {
        findImport(importId);
        return txns.findByImportIdOrderByRowNoAsc(importId).stream().map(this::toDto).toList();
    }

    // ---------------------------------------------------------------- internals

    private record Candidate(UUID lineId, UUID journalId, String entryNumber, LocalDate date, String narration,
                             String reference, boolean debit, BigDecimal amount) {
    }

    private List<Candidate> candidates(UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select l.id as line_id, e.id as journal_id, e.entry_number, e.entry_date, e.narration, e.reference,
                       l.debit, l.credit
                from journal_entry_lines l join journal_entries e on e.id = l.journal_entry_id
                where l.account_id = ? and e.status = 'POSTED' and e.entry_date between ? and ?
                order by e.entry_date, e.entry_number
                """, (rs, i) -> {
            BigDecimal dr = rs.getBigDecimal("debit");
            BigDecimal cr = rs.getBigDecimal("credit");
            return new Candidate(rs.getObject("line_id", UUID.class), rs.getObject("journal_id", UUID.class),
                    rs.getString("entry_number"), rs.getObject("entry_date", LocalDate.class), rs.getString("narration"),
                    rs.getString("reference"), dr.signum() > 0, Money.of(dr.signum() > 0 ? dr : cr));
        }, accountId, from, to);
    }

    /** True when a distinctive token (cheque/UTR/invoice number, 5+ chars with a digit) appears on both sides. */
    static boolean referenceMatch(Transaction t, Candidate c) {
        String bank = ((t.getReference() == null ? "" : t.getReference()) + " " + t.getDescription()).toUpperCase(Locale.ROOT);
        String book = ((c.reference == null ? "" : c.reference) + " " + c.narration).toUpperCase(Locale.ROOT);
        for (String token : bank.split("[^A-Z0-9/-]+")) {
            if (token.length() >= 5 && token.chars().anyMatch(Character::isDigit) && book.contains(token)) return true;
        }
        return false;
    }

    static String categoryHint(String description) {
        String d = description.toUpperCase(Locale.ROOT);
        if (d.matches(".*\\b(CHG|CHGS|CHARGES?|FEE|FEES|SMS ALERT|AMC|GST ON)\\b.*")) return "BANK_CHARGES";
        if (d.matches(".*\\b(INT\\.?PD|INT PD|INTEREST|INT CR|INT\\.CR|SB INT)\\b.*")) return "INTEREST";
        if (d.matches(".*\\b(ATM|CASH WDL|CASH WITHDRAWAL|CWDR|SELF)\\b.*")) return "CASH";
        if (d.matches(".*\\b(GST PMT|GSTN|CBDT|TDS|ITNS|TAX PAYMENT|CHALLAN)\\b.*")) return "TAX";
        if (d.matches(".*\\b(SALARY|SAL)\\b.*")) return "SALARY";
        return null;
    }

    private void requireInProgress(StatementImport imp) {
        if (imp.getStatus() != StatementImport.Status.IN_PROGRESS) {
            throw ApiException.conflict("RECONCILED", "This statement is already reconciled and signed off");
        }
    }

    private StatementImport findImport(UUID id) {
        return imports.findById(id).orElseThrow(() -> ApiException.notFound("Bank statement"));
    }

    private Transaction findTxn(UUID id) {
        return txns.findById(id).orElseThrow(() -> ApiException.notFound("Bank transaction"));
    }

    private ImportDto toDto(StatementImport i, List<String> warnings) {
        CurrentUser me = CurrentUser.orNull();
        boolean canApprove = me != null && me.has(JournalService.APPROVE) && i.getStatus() == StatementImport.Status.IN_PROGRESS
                && (!me.id().equals(i.getImportedBy()) || settings.allowSelfApproval());
        String accountName = accounts.findById(i.getLedgerAccountId()).map(a -> a.getCode() + " · " + a.getName()).orElse(null);
        return new ImportDto(i.getId(), i.getLedgerAccountId(), accountName, i.getBankFormat(), i.getOriginalFilename(),
                i.getPeriodFrom(), i.getPeriodTo(), str(i.getOpeningBalance()), str(i.getClosingBalance()),
                Money.str(i.getTotalDeposits()), Money.str(i.getTotalWithdrawals()), i.getLineCount(),
                i.getDuplicateCount(), txns.countByImportIdAndStatus(i.getId(), Transaction.Status.MATCHED),
                txns.countByImportIdAndStatus(i.getId(), Transaction.Status.UNMATCHED),
                txns.countByImportIdAndStatus(i.getId(), Transaction.Status.EXCLUDED), i.getStatus(),
                UserRef.of(users.findById(i.getImportedBy()).orElse(null)), i.getImportedAt(),
                i.getApprovedBy() == null ? null : UserRef.of(users.findById(i.getApprovedBy()).orElse(null)),
                i.getApprovedAt(), i.getApprovalComment(), i.isSelfApproved(), canApprove, warnings);
    }

    private TransactionDto toDto(Transaction t) {
        MatchedLine m = null;
        if (t.getMatchedLineId() != null) {
            m = jdbc.query("""
                    select e.id, e.entry_number, e.entry_date, e.narration, l.debit, l.credit
                    from journal_entry_lines l join journal_entries e on e.id = l.journal_entry_id where l.id = ?
                    """, (rs, i) -> new MatchedLine(t.getMatchedLineId(), rs.getObject("id", UUID.class),
                    rs.getString("entry_number"), rs.getObject("entry_date", LocalDate.class), rs.getString("narration"),
                    Money.str(rs.getBigDecimal("debit").signum() > 0 ? rs.getBigDecimal("debit") : rs.getBigDecimal("credit"))),
                    t.getMatchedLineId()).stream().findFirst().orElse(null);
        }
        return new TransactionDto(t.getId(), t.getRowNo(), t.getTxnDate(), t.getValueDate(), t.getDescription(),
                t.getReference(), Money.str(t.getWithdrawal()), Money.str(t.getDeposit()), str(t.getBalance()),
                t.getCategoryHint(), t.getStatus(), t.getMatchType(),
                t.getMatchConfidence() == null ? null : t.getMatchConfidence().intValue(), m, t.getExclusionReason());
    }

    private static String str(BigDecimal v) {
        return v == null ? null : Money.str(v);
    }
}
