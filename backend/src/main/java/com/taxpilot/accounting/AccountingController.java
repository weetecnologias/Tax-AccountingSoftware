package com.taxpilot.accounting;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.accounting.AccountingDtos.AccountDto;
import com.taxpilot.accounting.AccountingDtos.AccountRequest;
import com.taxpilot.accounting.AccountingDtos.ClientRef;
import com.taxpilot.accounting.AccountingDtos.JournalDetailDto;
import com.taxpilot.accounting.AccountingDtos.JournalRequest;
import com.taxpilot.accounting.AccountingDtos.JournalSummaryDto;
import com.taxpilot.accounting.AccountingDtos.OpenYearRequest;
import com.taxpilot.accounting.AccountingDtos.PeriodDto;
import com.taxpilot.accounting.AccountingDtos.ReasonRequest;
import com.taxpilot.accounting.AccountingDtos.ReverseRequest;
import com.taxpilot.common.PageResponse;
import com.taxpilot.common.Paging;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountingController {

    private final ChartOfAccountsService coa;
    private final PeriodService periods;
    private final JournalService journals;
    private final FinancialReportService reports;

    public AccountingController(ChartOfAccountsService coa, PeriodService periods, JournalService journals,
                                FinancialReportService reports) {
        this.coa = coa;
        this.periods = periods;
        this.journals = journals;
        this.reports = reports;
    }

    // ---- chart of accounts ----

    @GetMapping("/chart-of-accounts")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<AccountDto> chart(@RequestParam UUID clientId) {
        return coa.list(clientId);
    }

    @PostMapping("/chart-of-accounts/apply-template")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public List<AccountDto> applyTemplate(@Valid @RequestBody ClientRef ref) {
        return coa.applyTemplate(ref.clientId());
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public AccountDto createAccount(@Valid @RequestBody AccountRequest req) {
        return coa.create(req);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public AccountDto updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountRequest req) {
        return coa.update(id, req);
    }

    // ---- periods ----

    @GetMapping("/periods")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public List<PeriodDto> periods(@RequestParam UUID clientId) {
        return periods.list(clientId);
    }

    @PostMapping("/financial-years")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public List<PeriodDto> openYear(@Valid @RequestBody OpenYearRequest req) {
        return periods.openYear(req.clientId(), req.financialYear());
    }

    @PostMapping("/periods/{id}/close")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public PeriodDto closePeriod(@PathVariable UUID id) {
        return periods.close(id);
    }

    @PostMapping("/periods/{id}/reopen")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public PeriodDto reopenPeriod(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return periods.reopen(id, req.reason());
    }

    // ---- journals ----

    @GetMapping("/journals")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public PageResponse<JournalSummaryDto> journals(
            @RequestParam UUID clientId,
            @RequestParam(required = false) JournalEntry.Status status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return journals.list(clientId, status, from, to, q,
                Paging.of(page, size, null, Set.of("entryDate"), "entryDate"));
    }

    /** Send an Idempotency-Key header to make retries safe: a repeated key returns the original entry. */
    @PostMapping("/journals")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public ResponseEntity<JournalDetailDto> createJournal(
            @Valid @RequestBody JournalRequest req,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        JournalService.CreateResult result = journals.create(req, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.journal());
    }

    @GetMapping("/journals/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public JournalDetailDto journal(@PathVariable UUID id) {
        return journals.get(id);
    }

    @PutMapping("/journals/{id}")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public JournalDetailDto updateJournal(@PathVariable UUID id, @Valid @RequestBody JournalRequest req) {
        return journals.update(id, req);
    }

    @PostMapping("/journals/{id}/submit")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public JournalDetailDto submit(@PathVariable UUID id) {
        return journals.submit(id);
    }

    @PostMapping("/journals/{id}/approve")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public JournalDetailDto approve(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        return journals.approve(id, req == null ? null : req.reason());
    }

    @PostMapping("/journals/{id}/reject")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public JournalDetailDto reject(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        return journals.reject(id, req == null ? null : req.reason());
    }

    @PostMapping("/journals/{id}/post")
    @PreAuthorize("hasAuthority('ACCOUNTING_APPROVE')")
    public JournalDetailDto post(@PathVariable UUID id) {
        return journals.post(id);
    }

    @PostMapping("/journals/{id}/cancel")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public JournalDetailDto cancel(@PathVariable UUID id) {
        return journals.cancel(id);
    }

    @PostMapping("/journals/{id}/reverse")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public JournalDetailDto reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest req) {
        return journals.reverse(id, req.reversalDate(), req.reason());
    }

    // ---- reports ----

    @GetMapping("/ledger")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public FinancialReportService.GeneralLedger ledger(
            @RequestParam UUID clientId, @RequestParam UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.ledger(clientId, accountId, from, to);
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public FinancialReportService.TrialBalance trialBalance(
            @RequestParam UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.trialBalance(clientId, asOf);
    }

    @GetMapping("/profit-loss")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public FinancialReportService.ProfitAndLoss profitLoss(
            @RequestParam UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo) {
        return reports.profitAndLoss(clientId, from, to, compareFrom, compareTo);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public FinancialReportService.BalanceSheet balanceSheet(
            @RequestParam UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareAsOf) {
        return reports.balanceSheet(clientId, asOf, compareAsOf);
    }

    @GetMapping("/cash-flow")
    @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
    public FinancialReportService.CashFlow cashFlow(
            @RequestParam UUID clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.cashFlow(clientId, from, to);
    }

    /** Prepares the year-end closing journal as a DRAFT; it is then submitted, approved and posted as usual. */
    @PostMapping("/financial-years/{financialYear}/prepare-close")
    @PreAuthorize("hasAuthority('ACCOUNTING_WRITE')")
    public JournalDetailDto prepareYearEnd(@PathVariable String financialYear, @Valid @RequestBody ClientRef ref) {
        return journals.prepareYearEnd(ref.clientId(), financialYear);
    }
}
