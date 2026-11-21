package com.taxpilot.accounting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.accounting.AccountingDtos.PeriodDto;
import com.taxpilot.accounting.AccountingRepositories.JournalRepository;
import com.taxpilot.accounting.AccountingRepositories.PeriodRepository;
import com.taxpilot.audit.AuditService;
import com.taxpilot.clients.Client;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.users.UserDtos.UserRef;
import com.taxpilot.users.UserRepository;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final JournalRepository journals;
    private final ClientService clients;
    private final UserRepository users;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PeriodService(PeriodRepository periods, JournalRepository journals, ClientService clients,
                         UserRepository users, AuditService audit, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.periods = periods;
        this.journals = journals;
        this.clients = clients;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<PeriodDto> list(UUID clientId) {
        clients.find(clientId);
        return periods.findByClientIdOrderByStartDateAsc(clientId).stream().map(this::toDto).toList();
    }

    /** Creates the twelve monthly periods of a financial year, starting in the client's configured month. */
    @Transactional
    public List<PeriodDto> openYear(UUID clientId, String financialYear) {
        Client client = clients.findOpen(clientId);
        int startYear = parseFinancialYear(financialYear);
        LocalDate start = LocalDate.of(startYear, client.getFyStartMonth(), 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        if (periods.existsByClientIdAndFinancialYear(clientId, financialYear)) {
            throw ApiException.conflict("YEAR_EXISTS", "Financial year " + financialYear + " is already open");
        }
        if (periods.overlaps(clientId, start, end)) {
            throw ApiException.conflict("YEAR_OVERLAP", "These dates overlap an existing financial year");
        }
        List<AccountingPeriod> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate s = start.plusMonths(i);
            created.add(periods.save(new AccountingPeriod(clientId, financialYear, i + 1, s,
                    s.plusMonths(1).minusDays(1))));
        }
        audit.record("FINANCIAL_YEAR_OPENED", "CLIENT", clientId, clientId,
                Map.of("financialYear", financialYear, "from", start.toString(), "to", end.toString()));
        return created.stream().map(this::toDto).toList();
    }

    @Transactional
    public PeriodDto close(UUID periodId) {
        CurrentUser me = CurrentUser.require();
        AccountingPeriod p = find(periodId);
        if (p.getStatus() == AccountingPeriod.Status.CLOSED) {
            return toDto(p);
        }
        long unposted = journals.countByPeriodIdAndStatusIn(periodId,
                EnumSet.of(JournalEntry.Status.PENDING_APPROVAL, JournalEntry.Status.APPROVED));
        if (unposted > 0) {
            throw ApiException.conflict("UNPOSTED_JOURNALS", unposted
                    + " journal(s) in this period are awaiting approval or posting. Post, reject or cancel them first.");
        }
        p.close(me.id());
        periods.save(p);
        audit.record("PERIOD_CLOSED", "ACCOUNTING_PERIOD", periodId, p.getClientId(),
                Map.of("financialYear", p.getFinancialYear(), "period", p.getStartDate().toString()));
        return toDto(p);
    }

    @Transactional
    public PeriodDto reopen(UUID periodId, String reason) {
        AccountingPeriod p = find(periodId);
        if (reason == null || reason.isBlank()) {
            throw ApiException.field("reason", "Give a reason for reopening the period");
        }
        if (p.getStatus() == AccountingPeriod.Status.OPEN) {
            return toDto(p);
        }
        if (yearClosed(p.getClientId(), p.getFinancialYear())) {
            throw ApiException.conflict("YEAR_CLOSED", "Financial year " + p.getFinancialYear()
                    + " has a posted year-end closing entry, so its periods cannot be reopened");
        }
        clients.findOpen(p.getClientId());
        p.reopen();
        periods.save(p);
        audit.record("PERIOD_REOPENED", "ACCOUNTING_PERIOD", periodId, p.getClientId(),
                Map.of("financialYear", p.getFinancialYear(), "period", p.getStartDate().toString(),
                        "reason", reason.trim()));
        return toDto(p);
    }

    public List<AccountingPeriod> yearPeriods(UUID clientId, String financialYear) {
        return periods.findByClientIdAndFinancialYearOrderByPeriodNumberAsc(clientId, financialYear);
    }

    /** Closes every still-open period of the year (called when the year-end closing entry is posted). */
    @Transactional
    public int closeYear(UUID clientId, String financialYear, UUID by) {
        int n = 0;
        for (AccountingPeriod p : yearPeriods(clientId, financialYear)) {
            if (p.getStatus() == AccountingPeriod.Status.OPEN) {
                p.close(by);
                periods.save(p);
                n++;
            }
        }
        return n;
    }

    boolean yearClosed(UUID clientId, String financialYear) {
        Integer n = jdbc.queryForObject("""
                select count(*) from journal_entries
                where client_id = ? and closing_financial_year = ? and status = 'POSTED'
                """, Integer.class, clientId, financialYear);
        return n != null && n > 0;
    }

    public AccountingPeriod find(UUID id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Accounting period"));
    }

    /** The period containing the date, which must exist. */
    public AccountingPeriod periodFor(UUID clientId, LocalDate date, String field) {
        return periods.findContaining(clientId, date).orElseThrow(() -> ApiException.field(field,
                "No financial year is open for " + date + ". Open the financial year first."));
    }

    static int parseFinancialYear(String fy) {
        if (fy == null || !fy.matches("^\\d{4}-\\d{2}$")) {
            throw ApiException.field("financialYear", "Use the format 2025-26");
        }
        int start = Integer.parseInt(fy.substring(0, 4));
        int end = Integer.parseInt(fy.substring(5));
        if ((start + 1) % 100 != end || start < 2000 || start > 2099) {
            throw ApiException.field("financialYear", "Financial year must be consecutive years, e.g. 2025-26");
        }
        return start;
    }

    PeriodDto toDto(AccountingPeriod p) {
        return new PeriodDto(p.getId(), p.getFinancialYear(), p.getPeriodNumber(), p.getStartDate(), p.getEndDate(),
                p.getStatus(), p.getClosedBy() == null ? null : UserRef.of(users.findById(p.getClosedBy()).orElse(null)),
                p.getClosedAt(), yearClosed(p.getClientId(), p.getFinancialYear()), p.getVersion());
    }
}
