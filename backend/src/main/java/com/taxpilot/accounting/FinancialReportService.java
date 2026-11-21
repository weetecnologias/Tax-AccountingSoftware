package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.clients.Client;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;

/**
 * Deterministic financial statements computed in SQL (NUMERIC) from POSTED journal lines only.
 * Drafts, pending and cancelled journals never affect any figure.
 */
@Service
@Transactional(readOnly = true)
public class FinancialReportService {

    private final JdbcTemplate jdbc;
    private final ClientService clients;
    private final ChartOfAccountsService accounts;

    public FinancialReportService(JdbcTemplate jdbc, ClientService clients, ChartOfAccountsService accounts) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.accounts = accounts;
    }

    public record ReportHeader(UUID clientId, String clientCode, String clientName, LocalDate from, LocalDate to,
                               Instant generatedAt, String basis) {
    }

    // ---------------------------------------------------------------- trial balance

    public record TrialBalanceRow(UUID accountId, String code, String name, AccountType accountType, String groupName,
                                  String debit, String credit) {
    }

    public record TrialBalance(ReportHeader header, List<TrialBalanceRow> rows, String totalDebit, String totalCredit,
                               boolean balanced) {
    }

    public TrialBalance trialBalance(UUID clientId, LocalDate asOf) {
        ReportHeader h = header(clientId, null, asOf);
        List<TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal td = Money.ZERO;
        BigDecimal tc = Money.ZERO;
        for (Balance b : balances(clientId, null, asOf)) {
            BigDecimal net = b.debit.subtract(b.credit);
            if (net.signum() == 0) continue;
            BigDecimal dr = net.signum() > 0 ? net : Money.ZERO;
            BigDecimal cr = net.signum() < 0 ? net.negate() : Money.ZERO;
            td = td.add(dr);
            tc = tc.add(cr);
            rows.add(new TrialBalanceRow(b.id, b.code, b.name, b.type, b.groupName, Money.str(dr), Money.str(cr)));
        }
        return new TrialBalance(h, rows, Money.str(td), Money.str(tc), td.compareTo(tc) == 0);
    }

    // ---------------------------------------------------------------- profit and loss

    public record StatementLine(UUID accountId, String code, String name, String amount) {
    }

    public record StatementSection(String title, List<StatementLine> lines, String total) {
    }

    public record ProfitAndLoss(ReportHeader header, List<StatementSection> income, String totalIncome,
                                List<StatementSection> expenses, String totalExpenses, String netProfit) {
    }

    public ProfitAndLoss profitAndLoss(UUID clientId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("BAD_RANGE", "Choose a valid date range");
        }
        ReportHeader h = header(clientId, from, to);
        List<Balance> bal = balances(clientId, from, to);
        Grouped income = group(bal, AccountType.INCOME);
        Grouped expenses = group(bal, AccountType.EXPENSE);
        return new ProfitAndLoss(h, income.sections, Money.str(income.total), expenses.sections,
                Money.str(expenses.total), Money.str(income.total.subtract(expenses.total)));
    }

    // ---------------------------------------------------------------- balance sheet

    public record BalanceSheet(ReportHeader header, List<StatementSection> assets, String totalAssets,
                               List<StatementSection> liabilities, String totalLiabilities,
                               List<StatementSection> equity, String profitForPeriod, String totalEquity,
                               String totalLiabilitiesAndEquity, boolean balanced, String difference) {
    }

    public BalanceSheet balanceSheet(UUID clientId, LocalDate asOf) {
        ReportHeader h = header(clientId, null, asOf);
        List<Balance> bal = balances(clientId, null, asOf);
        Grouped assets = group(bal, AccountType.ASSET);
        Grouped liabilities = group(bal, AccountType.LIABILITY);
        Grouped equity = group(bal, AccountType.EQUITY);
        BigDecimal profit = group(bal, AccountType.INCOME).total.subtract(group(bal, AccountType.EXPENSE).total);
        BigDecimal totalEquity = equity.total.add(profit);
        BigDecimal le = liabilities.total.add(totalEquity);
        BigDecimal diff = assets.total.subtract(le);
        return new BalanceSheet(h, assets.sections, Money.str(assets.total), liabilities.sections,
                Money.str(liabilities.total), equity.sections, Money.str(profit), Money.str(totalEquity),
                Money.str(le), diff.signum() == 0, Money.str(diff.abs()));
    }

    // ---------------------------------------------------------------- general ledger

    public record LedgerLine(UUID journalId, String entryNumber, LocalDate entryDate, String narration,
                             String description, String debit, String credit, String balance, String balanceSide) {
    }

    public record GeneralLedger(ReportHeader header, UUID accountId, String code, String name, AccountType accountType,
                                String openingBalance, String openingSide, List<LedgerLine> lines, String totalDebit,
                                String totalCredit, String closingBalance, String closingSide) {
    }

    public GeneralLedger ledger(UUID clientId, UUID accountId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("BAD_RANGE", "Choose a valid date range");
        }
        LedgerAccount a = accounts.find(accountId);
        if (!a.getClientId().equals(clientId)) {
            throw ApiException.notFound("Account");
        }
        if (a.isGroup()) {
            throw ApiException.badRequest("GROUP_ACCOUNT", "Choose a ledger account, not a group");
        }
        ReportHeader h = header(clientId, from, to);
        BigDecimal opening = jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0)
                from journal_entry_lines l join journal_entries e on e.id = l.journal_entry_id
                where l.account_id = ? and e.status = 'POSTED' and e.entry_date < ?
                """, BigDecimal.class, accountId, from);
        BigDecimal running = Money.of(opening);
        BigDecimal[] totals = {Money.ZERO, Money.ZERO};
        List<LedgerLine> lines = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id, e.entry_number, e.entry_date, e.narration, l.description, l.debit, l.credit
                from journal_entry_lines l join journal_entries e on e.id = l.journal_entry_id
                where l.account_id = ? and e.status = 'POSTED' and e.entry_date between ? and ?
                order by e.entry_date, e.entry_number, l.line_no
                """, accountId, from, to);
        for (Map<String, Object> r : rows) {
            BigDecimal dr = Money.of((BigDecimal) r.get("debit"));
            BigDecimal cr = Money.of((BigDecimal) r.get("credit"));
            running = running.add(dr).subtract(cr);
            totals[0] = totals[0].add(dr);
            totals[1] = totals[1].add(cr);
            lines.add(new LedgerLine((UUID) r.get("id"), (String) r.get("entry_number"),
                    ((java.sql.Date) r.get("entry_date")).toLocalDate(), (String) r.get("narration"),
                    (String) r.get("description"), Money.str(dr), Money.str(cr), Money.str(running.abs()),
                    side(running)));
        }
        return new GeneralLedger(h, a.getId(), a.getCode(), a.getName(), a.getAccountType(), Money.str(opening.abs()),
                side(opening), lines, Money.str(totals[0]), Money.str(totals[1]), Money.str(running.abs()),
                side(running));
    }

    // ---------------------------------------------------------------- internals

    private record Balance(UUID id, String code, String name, AccountType type, String groupName, BigDecimal debit,
                           BigDecimal credit) {
    }

    private record Grouped(List<StatementSection> sections, BigDecimal total) {
    }

    /** Per-ledger debit/credit totals of POSTED lines in the range (from may be null for "since inception"). */
    private List<Balance> balances(UUID clientId, LocalDate from, LocalDate to) {
        String sql = """
                select a.id, a.code, a.name, a.account_type, coalesce(p.name, '') as group_name,
                       coalesce(sum(x.debit), 0) as debit, coalesce(sum(x.credit), 0) as credit
                from ledger_accounts a
                left join ledger_accounts p on p.id = a.parent_id
                left join (
                    select l.account_id, l.debit, l.credit
                    from journal_entry_lines l join journal_entries e on e.id = l.journal_entry_id
                    where e.client_id = ? and e.status = 'POSTED' and e.entry_date <= ?
                      and (cast(? as date) is null or e.entry_date >= cast(? as date))
                ) x on x.account_id = a.id
                where a.client_id = ? and not a.is_group
                group by a.id, a.code, a.name, a.account_type, p.name
                order by a.code
                """;
        return jdbc.query(sql, (rs, i) -> new Balance(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), AccountType.valueOf(rs.getString("account_type")),
                        rs.getString("group_name"), Money.of(rs.getBigDecimal("debit")),
                        Money.of(rs.getBigDecimal("credit"))),
                clientId, to, from, from, clientId);
    }

    /** Groups non-zero ledgers of one type under their parent group, signed by the type's normal balance. */
    private Grouped group(List<Balance> all, AccountType type) {
        Map<String, List<StatementLine>> byGroup = new LinkedHashMap<>();
        Map<String, BigDecimal> groupTotals = new LinkedHashMap<>();
        BigDecimal total = Money.ZERO;
        for (Balance b : all) {
            if (b.type != type) continue;
            BigDecimal amount = type.isDebitNormal() ? b.debit.subtract(b.credit) : b.credit.subtract(b.debit);
            if (amount.signum() == 0) continue;
            String g = b.groupName.isBlank() ? "Other" : b.groupName;
            byGroup.computeIfAbsent(g, k -> new ArrayList<>())
                    .add(new StatementLine(b.id, b.code, b.name, Money.str(amount)));
            groupTotals.merge(g, amount, BigDecimal::add);
            total = total.add(amount);
        }
        List<StatementSection> sections = new ArrayList<>();
        byGroup.forEach((g, lines) -> sections.add(new StatementSection(g, lines, Money.str(groupTotals.get(g)))));
        return new Grouped(sections, total);
    }

    private ReportHeader header(UUID clientId, LocalDate from, LocalDate to) {
        if (to == null) {
            throw ApiException.badRequest("BAD_RANGE", "Choose a date");
        }
        Client c = clients.find(clientId);
        return new ReportHeader(clientId, c.getClientCode(), c.getDisplayName(), from, to, Instant.now(),
                "Posted journal entries only");
    }

    private static String side(BigDecimal net) {
        return net.signum() > 0 ? "Dr" : net.signum() < 0 ? "Cr" : "";
    }
}
