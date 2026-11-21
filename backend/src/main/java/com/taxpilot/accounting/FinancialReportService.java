package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.taxpilot.settings.SettingsService;

/**
 * Deterministic financial statements computed in SQL (NUMERIC) from POSTED journal lines only.
 * Drafts, pending and cancelled journals never affect any figure. Year-end CLOSING entries are excluded from the
 * profit and loss account and the cash flow statement (they only move profit into retained earnings).
 */
@Service
@Transactional(readOnly = true)
public class FinancialReportService {

    private final JdbcTemplate jdbc;
    private final ClientService clients;
    private final ChartOfAccountsService accounts;
    private final SettingsService settings;

    public FinancialReportService(JdbcTemplate jdbc, ClientService clients, ChartOfAccountsService accounts,
                                  SettingsService settings) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.accounts = accounts;
        this.settings = settings;
    }

    public record ReportHeader(UUID clientId, String clientCode, String clientName, LocalDate from, LocalDate to,
                               Instant generatedAt, String basis, SettingsService.Letterhead preparedBy,
                               LocalDate compareFrom, LocalDate compareTo) {
    }

    // ---------------------------------------------------------------- trial balance

    public record TrialBalanceRow(UUID accountId, String code, String name, AccountType accountType, String groupName,
                                  String debit, String credit) {
    }

    public record TrialBalance(ReportHeader header, List<TrialBalanceRow> rows, String totalDebit, String totalCredit,
                               boolean balanced) {
    }

    public TrialBalance trialBalance(UUID clientId, LocalDate asOf) {
        ReportHeader h = header(clientId, null, asOf, null, null);
        List<TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal td = Money.ZERO;
        BigDecimal tc = Money.ZERO;
        for (Balance b : balances(clientId, null, asOf, false)) {
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

    /** {@code previous} is null when no comparison period was requested. */
    public record StatementLine(UUID accountId, String code, String name, String amount, String previous) {
    }

    public record StatementSection(String title, List<StatementLine> lines, String total, String previousTotal) {
    }

    public record ProfitAndLoss(ReportHeader header, List<StatementSection> income, String totalIncome,
                                String previousTotalIncome, List<StatementSection> expenses, String totalExpenses,
                                String previousTotalExpenses, String netProfit, String previousNetProfit) {
    }

    public ProfitAndLoss profitAndLoss(UUID clientId, LocalDate from, LocalDate to, LocalDate compareFrom,
                                       LocalDate compareTo) {
        requireRange(from, to);
        boolean compare = compareFrom != null && compareTo != null;
        if (compare) requireRange(compareFrom, compareTo);
        ReportHeader h = header(clientId, from, to, compare ? compareFrom : null, compare ? compareTo : null);
        List<Balance> cur = balances(clientId, from, to, true);
        List<Balance> prev = compare ? balances(clientId, compareFrom, compareTo, true) : null;
        Grouped income = group(cur, prev, AccountType.INCOME);
        Grouped expenses = group(cur, prev, AccountType.EXPENSE);
        return new ProfitAndLoss(h, income.sections, Money.str(income.total), compare ? Money.str(income.prevTotal) : null,
                expenses.sections, Money.str(expenses.total), compare ? Money.str(expenses.prevTotal) : null,
                Money.str(income.total.subtract(expenses.total)),
                compare ? Money.str(income.prevTotal.subtract(expenses.prevTotal)) : null);
    }

    // ---------------------------------------------------------------- balance sheet

    public record BalanceSheet(ReportHeader header, List<StatementSection> assets, String totalAssets,
                               String previousTotalAssets, List<StatementSection> liabilities, String totalLiabilities,
                               String previousTotalLiabilities, List<StatementSection> equity, String profitForPeriod,
                               String previousProfitForPeriod, String totalEquity, String previousTotalEquity,
                               String totalLiabilitiesAndEquity, String previousTotalLiabilitiesAndEquity,
                               boolean balanced, String difference) {
    }

    public BalanceSheet balanceSheet(UUID clientId, LocalDate asOf, LocalDate compareAsOf) {
        boolean compare = compareAsOf != null;
        ReportHeader h = header(clientId, null, asOf, null, compareAsOf);
        List<Balance> cur = balances(clientId, null, asOf, false);
        List<Balance> prev = compare ? balances(clientId, null, compareAsOf, false) : null;
        Grouped assets = group(cur, prev, AccountType.ASSET);
        Grouped liabilities = group(cur, prev, AccountType.LIABILITY);
        Grouped equity = group(cur, prev, AccountType.EQUITY);
        Grouped inc = group(cur, prev, AccountType.INCOME);
        Grouped exp = group(cur, prev, AccountType.EXPENSE);
        // Unclosed profit: income less expenses still sitting in P&L ledgers (closing entries move it to equity).
        BigDecimal profit = inc.total.subtract(exp.total);
        BigDecimal prevProfit = inc.prevTotal.subtract(exp.prevTotal);
        BigDecimal totalEquity = equity.total.add(profit);
        BigDecimal prevEquity = equity.prevTotal.add(prevProfit);
        BigDecimal le = liabilities.total.add(totalEquity);
        BigDecimal prevLe = liabilities.prevTotal.add(prevEquity);
        BigDecimal diff = assets.total.subtract(le);
        return new BalanceSheet(h, assets.sections, Money.str(assets.total), compare ? Money.str(assets.prevTotal) : null,
                liabilities.sections, Money.str(liabilities.total), compare ? Money.str(liabilities.prevTotal) : null,
                equity.sections, Money.str(profit), compare ? Money.str(prevProfit) : null, Money.str(totalEquity),
                compare ? Money.str(prevEquity) : null, Money.str(le), compare ? Money.str(prevLe) : null,
                diff.signum() == 0, Money.str(diff.abs()));
    }

    // ---------------------------------------------------------------- cash flow (indirect method)

    public record CashFlowLine(UUID accountId, String code, String label, String amount) {
    }

    public record CashFlowSection(String title, List<CashFlowLine> lines, String total) {
    }

    public record CashFlow(ReportHeader header, String netProfit, CashFlowSection nonCashAdjustments,
                           CashFlowSection workingCapital, String operatingTotal, CashFlowSection investing,
                           CashFlowSection financing, String netChange, String openingCash, String closingCash,
                           boolean reconciles, List<String> unclassifiedAccounts) {
    }

    /**
     * Net profit (excluding closing entries) adjusted for non-cash items and working-capital movements, followed by
     * investing and financing movements. Each balance-sheet ledger's cash-flow category decides its section.
     * By double entry, opening cash + net change always equals closing cash; the check is reported anyway.
     */
    public CashFlow cashFlow(UUID clientId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        ReportHeader h = header(clientId, from, to, null, null);
        Map<UUID, String> category = new HashMap<>();
        jdbc.query("select id, cash_flow_category from ledger_accounts where client_id = ?",
                rs -> { category.put(rs.getObject("id", UUID.class), rs.getString("cash_flow_category")); }, clientId);
        List<Balance> movements = balances(clientId, from, to, true);
        BigDecimal netProfit = Money.ZERO;
        BigDecimal cashMovement = Money.ZERO;
        List<CashFlowLine> nonCash = new ArrayList<>();
        List<CashFlowLine> working = new ArrayList<>();
        List<CashFlowLine> investing = new ArrayList<>();
        List<CashFlowLine> financing = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        for (Balance b : movements) {
            BigDecimal dr = b.debit.subtract(b.credit);
            if (b.type == AccountType.INCOME || b.type == AccountType.EXPENSE) {
                netProfit = netProfit.subtract(dr);
                continue;
            }
            if (dr.signum() == 0) continue;
            String cat = category.get(b.id);
            BigDecimal effect = dr.negate(); // a debit to a non-cash account consumed cash
            if ("CASH".equals(cat)) {
                cashMovement = cashMovement.add(dr);
                continue;
            }
            if (cat == null) {
                unclassified.add(b.code + " " + b.name);
                cat = "OPERATING";
            }
            String label = (b.type == AccountType.ASSET ? (dr.signum() > 0 ? "Increase in " : "Decrease in ")
                    : (dr.signum() < 0 ? "Increase in " : "Decrease in ")) + b.name;
            CashFlowLine line = new CashFlowLine(b.id, b.code, "NON_CASH".equals(cat) ? b.name : label, Money.str(effect));
            switch (cat) {
                case "NON_CASH" -> nonCash.add(line);
                case "INVESTING" -> investing.add(line);
                case "FINANCING" -> financing.add(line);
                default -> working.add(line);
            }
        }
        BigDecimal nonCashTotal = sum(nonCash);
        BigDecimal workingTotal = sum(working);
        BigDecimal operating = netProfit.add(nonCashTotal).add(workingTotal);
        BigDecimal investingTotal = sum(investing);
        BigDecimal financingTotal = sum(financing);
        BigDecimal netChange = operating.add(investingTotal).add(financingTotal);
        BigDecimal opening = cashBalance(clientId, category, from.minusDays(1));
        BigDecimal closing = cashBalance(clientId, category, to);
        boolean reconciles = opening.add(netChange).compareTo(closing) == 0 && netChange.compareTo(cashMovement) == 0;
        return new CashFlow(h, Money.str(netProfit),
                new CashFlowSection("Adjustments for non-cash items", nonCash, Money.str(nonCashTotal)),
                new CashFlowSection("Changes in working capital", working, Money.str(workingTotal)),
                Money.str(operating),
                new CashFlowSection("Cash flows from investing activities", investing, Money.str(investingTotal)),
                new CashFlowSection("Cash flows from financing activities", financing, Money.str(financingTotal)),
                Money.str(netChange), Money.str(opening), Money.str(closing), reconciles, unclassified);
    }

    private BigDecimal cashBalance(UUID clientId, Map<UUID, String> category, LocalDate asOf) {
        BigDecimal total = Money.ZERO;
        for (Balance b : balances(clientId, null, asOf, false)) {
            if ("CASH".equals(category.get(b.id))) total = total.add(b.debit.subtract(b.credit));
        }
        return total;
    }

    private static BigDecimal sum(List<CashFlowLine> lines) {
        BigDecimal t = Money.ZERO;
        for (CashFlowLine l : lines) t = t.add(new BigDecimal(l.amount()));
        return t;
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
        requireRange(from, to);
        LedgerAccount a = accounts.find(accountId);
        if (!a.getClientId().equals(clientId)) {
            throw ApiException.notFound("Account");
        }
        if (a.isGroup()) {
            throw ApiException.badRequest("GROUP_ACCOUNT", "Choose a ledger account, not a group");
        }
        ReportHeader h = header(clientId, from, to, null, null);
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

    private record Grouped(List<StatementSection> sections, BigDecimal total, BigDecimal prevTotal) {
    }

    /** Per-ledger debit/credit totals of POSTED lines in the range (from may be null for "since inception"). */
    private List<Balance> balances(UUID clientId, LocalDate from, LocalDate to, boolean excludeClosing) {
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
                      and (? = false or e.entry_type <> 'CLOSING')
                ) x on x.account_id = a.id
                where a.client_id = ? and not a.is_group
                group by a.id, a.code, a.name, a.account_type, p.name
                order by a.code
                """;
        return jdbc.query(sql, (rs, i) -> new Balance(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), AccountType.valueOf(rs.getString("account_type")),
                        rs.getString("group_name"), Money.of(rs.getBigDecimal("debit")),
                        Money.of(rs.getBigDecimal("credit"))),
                clientId, to, from, from, excludeClosing, clientId);
    }

    /** Groups non-zero ledgers of one type under their parent group, signed by the type's normal balance. */
    private Grouped group(List<Balance> cur, List<Balance> prev, AccountType type) {
        Map<UUID, BigDecimal> prevAmounts = new HashMap<>();
        if (prev != null) {
            for (Balance b : prev) {
                if (b.type == type) prevAmounts.put(b.id, signed(b, type));
            }
        }
        Map<String, List<StatementLine>> byGroup = new LinkedHashMap<>();
        Map<String, BigDecimal[]> groupTotals = new LinkedHashMap<>();
        BigDecimal total = Money.ZERO;
        BigDecimal prevTotal = Money.ZERO;
        for (Balance b : cur) {
            if (b.type != type) continue;
            BigDecimal amount = signed(b, type);
            BigDecimal before = prevAmounts.getOrDefault(b.id, Money.ZERO);
            if (amount.signum() == 0 && before.signum() == 0) continue;
            String g = b.groupName.isBlank() ? "Other" : b.groupName;
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(new StatementLine(b.id, b.code, b.name,
                    Money.str(amount), prev == null ? null : Money.str(before)));
            BigDecimal[] t = groupTotals.computeIfAbsent(g, k -> new BigDecimal[]{Money.ZERO, Money.ZERO});
            t[0] = t[0].add(amount);
            t[1] = t[1].add(before);
            total = total.add(amount);
            prevTotal = prevTotal.add(before);
        }
        List<StatementSection> sections = new ArrayList<>();
        byGroup.forEach((g, lines) -> sections.add(new StatementSection(g, lines, Money.str(groupTotals.get(g)[0]),
                prev == null ? null : Money.str(groupTotals.get(g)[1]))));
        return new Grouped(sections, total, prevTotal);
    }

    private static BigDecimal signed(Balance b, AccountType type) {
        return type.isDebitNormal() ? b.debit.subtract(b.credit) : b.credit.subtract(b.debit);
    }

    private ReportHeader header(UUID clientId, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {
        if (to == null) {
            throw ApiException.badRequest("BAD_RANGE", "Choose a date");
        }
        Client c = clients.find(clientId);
        return new ReportHeader(clientId, c.getClientCode(), c.getDisplayName(), from, to, Instant.now(),
                "Posted journal entries only", settings.letterhead(), compareFrom, compareTo);
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("BAD_RANGE", "Choose a valid date range");
        }
    }

    private static String side(BigDecimal net) {
        return net.signum() > 0 ? "Dr" : net.signum() < 0 ? "Cr" : "";
    }
}
