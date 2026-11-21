package com.taxpilot.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taxpilot.accounting.FinancialReportService;
import com.taxpilot.accounting.FinancialReportService.StatementSection;
import com.taxpilot.audit.AuditService;
import com.taxpilot.common.ApiException;
import com.taxpilot.invoicing.ReceivablesService;
import com.taxpilot.security.CurrentUser;
import com.taxpilot.settings.SettingsService;
import com.taxpilot.users.UserRepository;

/** Builds report documents from the deterministic report services and renders them to PDF or XLSX. */
@Service
public class ReportExportService {

    public enum Report { TRIAL_BALANCE, PROFIT_LOSS, BALANCE_SHEET, CASH_FLOW, LEDGER, AGEING_RECEIVABLE, AGEING_PAYABLE,
        SALES_REGISTER, PURCHASE_REGISTER }

    public enum Format { PDF, XLSX }

    public record Exported(String filename, String contentType, byte[] data) {
    }

    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final FinancialReportService reports;
    private final ReceivablesService receivables;
    private final SettingsService settings;
    private final UserRepository users;
    private final AuditService audit;

    public ReportExportService(FinancialReportService reports, ReceivablesService receivables, SettingsService settings,
                               UserRepository users, AuditService audit) {
        this.reports = reports;
        this.receivables = receivables;
        this.settings = settings;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public Exported export(Report report, Format format, UUID clientId, LocalDate from, LocalDate to, LocalDate asOf,
                           UUID accountId, LocalDate compareFrom, LocalDate compareTo, LocalDate compareAsOf) {
        ReportDocument doc = switch (report) {
            case TRIAL_BALANCE -> trialBalance(reports.trialBalance(clientId, need(asOf, "asOf")));
            case PROFIT_LOSS -> profitLoss(reports.profitAndLoss(clientId, need(from, "from"), need(to, "to"), compareFrom, compareTo));
            case BALANCE_SHEET -> balanceSheet(reports.balanceSheet(clientId, need(asOf, "asOf"), compareAsOf));
            case CASH_FLOW -> cashFlow(reports.cashFlow(clientId, need(from, "from"), need(to, "to")));
            case LEDGER -> ledger(reports.ledger(clientId, needId(accountId), need(from, "from"), need(to, "to")));
            case AGEING_RECEIVABLE -> ageing(clientId, receivables.ageing(clientId, ReceivablesService.Kind.RECEIVABLE, need(asOf, "asOf")));
            case AGEING_PAYABLE -> ageing(clientId, receivables.ageing(clientId, ReceivablesService.Kind.PAYABLE, need(asOf, "asOf")));
            case SALES_REGISTER -> register(clientId, receivables.register(clientId, "SALES", need(from, "from"), need(to, "to")));
            case PURCHASE_REGISTER -> register(clientId, receivables.register(clientId, "PURCHASE", need(from, "from"), need(to, "to")));
        };
        byte[] data = format == Format.PDF ? ReportRenderer.pdf(doc) : ReportRenderer.xlsx(doc);
        String base = report.name().toLowerCase().replace('_', '-') + "-" + (asOf != null ? asOf : from + "-to-" + to);
        audit.record("REPORT_EXPORTED", "CLIENT", clientId, clientId, Map.of("report", report.name(), "format", format.name()));
        return format == Format.PDF ? new Exported(base + ".pdf", "application/pdf", data)
                : new Exported(base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", data);
    }

    // ---------------------------------------------------------------- builders

    private ReportDocument doc(String title, FinancialReportService.ReportHeader h) {
        String period = h.from() == null ? "As at " + D.format(h.to()) : "For the period " + D.format(h.from()) + " to " + D.format(h.to());
        if (h.compareFrom() != null) period += " · compared with " + D.format(h.compareFrom()) + " to " + D.format(h.compareTo());
        else if (h.compareTo() != null) period += " · compared with " + D.format(h.compareTo());
        return new ReportDocument(title, h.clientName() + " (" + h.clientCode() + ")", period + " · amounts in ₹",
                h.preparedBy(), me(), Instant.now()).note("Basis: " + h.basis() + ".");
    }

    private ReportDocument trialBalance(FinancialReportService.TrialBalance tb) {
        ReportDocument d = doc("Trial Balance", tb.header()).column("Code", false, 12).column("Account", false, 46)
                .column("Group", false, 30).column("Debit", true, 20).column("Credit", true, 20);
        tb.rows().forEach(r -> d.row(ReportDocument.Style.NORMAL, r.code(), r.name(), r.groupName(), blankZero(r.debit()), blankZero(r.credit())));
        d.row(ReportDocument.Style.TOTAL, "", "Total", "", tb.totalDebit(), tb.totalCredit());
        d.note(tb.balanced() ? "Total debits equal total credits." : "WARNING: the trial balance does not balance.");
        return d;
    }

    private ReportDocument profitLoss(FinancialReportService.ProfitAndLoss pl) {
        boolean cmp = pl.previousNetProfit() != null;
        ReportDocument d = doc("Statement of Profit and Loss", pl.header()).column("Particulars", false, 70).column("Amount", true, 22);
        if (cmp) d.column("Previous", true, 22);
        statement(d, "Income", pl.income(), "Total income", pl.totalIncome(), pl.previousTotalIncome(), cmp);
        statement(d, "Expenses", pl.expenses(), "Total expenses", pl.totalExpenses(), pl.previousTotalExpenses(), cmp);
        d.row(ReportDocument.Style.TOTAL, cells(cmp, "Net profit / (loss)", pl.netProfit(), pl.previousNetProfit()));
        return d;
    }

    private ReportDocument balanceSheet(FinancialReportService.BalanceSheet bs) {
        boolean cmp = bs.previousTotalAssets() != null;
        ReportDocument d = doc("Balance Sheet", bs.header()).column("Particulars", false, 70).column("Amount", true, 22);
        if (cmp) d.column("Previous", true, 22);
        statement(d, "Equity", bs.equity(), null, null, null, cmp);
        d.row(ReportDocument.Style.NORMAL, cells(cmp, "    Profit / (loss) for the period", bs.profitForPeriod(), bs.previousProfitForPeriod()));
        d.row(ReportDocument.Style.SUBTOTAL, cells(cmp, "Total equity (including profit for the period)", bs.totalEquity(), bs.previousTotalEquity()));
        statement(d, "Liabilities", bs.liabilities(), "Total liabilities", bs.totalLiabilities(), bs.previousTotalLiabilities(), cmp);
        d.row(ReportDocument.Style.TOTAL, cells(cmp, "Total equity and liabilities", bs.totalLiabilitiesAndEquity(), bs.previousTotalLiabilitiesAndEquity()));
        statement(d, "Assets", bs.assets(), null, null, null, cmp);
        d.row(ReportDocument.Style.TOTAL, cells(cmp, "Total assets", bs.totalAssets(), bs.previousTotalAssets()));
        d.note(bs.balanced() ? "Assets equal equity plus liabilities." : "WARNING: out of balance by ₹" + bs.difference() + ".");
        return d;
    }

    private void statement(ReportDocument d, String heading, List<StatementSection> sections, String totalLabel,
                           String total, String prevTotal, boolean cmp) {
        d.row(ReportDocument.Style.SECTION, cells(cmp, heading, "", ""));
        for (StatementSection s : sections) {
            d.row(ReportDocument.Style.NORMAL, cells(cmp, "  " + s.title(), "", ""));
            s.lines().forEach(l -> d.row(ReportDocument.Style.NORMAL, cells(cmp, "      " + l.code() + "  " + l.name(), l.amount(), l.previous())));
            d.row(ReportDocument.Style.MUTED, cells(cmp, "  Total " + s.title(), s.total(), s.previousTotal()));
        }
        if (totalLabel != null) d.row(ReportDocument.Style.SUBTOTAL, cells(cmp, totalLabel, total, prevTotal));
    }

    private ReportDocument cashFlow(FinancialReportService.CashFlow cf) {
        ReportDocument d = doc("Cash Flow Statement (indirect method)", cf.header()).column("Particulars", false, 80).column("Amount", true, 24);
        d.row(ReportDocument.Style.SECTION, "Cash flows from operating activities", "");
        d.row(ReportDocument.Style.NORMAL, "  Net profit / (loss)", cf.netProfit());
        for (FinancialReportService.CashFlowSection s : List.of(cf.nonCashAdjustments(), cf.workingCapital())) {
            d.row(ReportDocument.Style.NORMAL, "  " + s.title(), "");
            s.lines().forEach(l -> d.row(ReportDocument.Style.NORMAL, "      " + l.label(), l.amount()));
        }
        d.row(ReportDocument.Style.SUBTOTAL, "Net cash from operating activities", cf.operatingTotal());
        for (FinancialReportService.CashFlowSection s : List.of(cf.investing(), cf.financing())) {
            d.row(ReportDocument.Style.SECTION, s.title(), "");
            s.lines().forEach(l -> d.row(ReportDocument.Style.NORMAL, "      " + l.label(), l.amount()));
            d.row(ReportDocument.Style.SUBTOTAL, "Net cash from " + s.title().replace("Cash flows from ", ""), s.total());
        }
        d.row(ReportDocument.Style.TOTAL, "Net increase / (decrease) in cash and cash equivalents", cf.netChange());
        d.row(ReportDocument.Style.NORMAL, "Cash and cash equivalents at the beginning", cf.openingCash());
        d.row(ReportDocument.Style.TOTAL, "Cash and cash equivalents at the end", cf.closingCash());
        if (!cf.unclassifiedAccounts().isEmpty()) d.note("Unclassified accounts treated as operating: " + String.join(", ", cf.unclassifiedAccounts()));
        return d;
    }

    private ReportDocument ledger(FinancialReportService.GeneralLedger gl) {
        ReportDocument d = doc("General Ledger — " + gl.code() + " " + gl.name(), gl.header())
                .column("Date", false, 16).column("Voucher", false, 22).column("Narration", false, 60)
                .column("Debit", true, 18).column("Credit", true, 18).column("Balance", true, 20).column("", false, 6);
        d.row(ReportDocument.Style.SECTION, "", "", "Opening balance", "", "", gl.openingBalance(), gl.openingSide());
        gl.lines().forEach(l -> d.row(ReportDocument.Style.NORMAL, D.format(l.entryDate()), l.entryNumber(),
                l.description() != null ? l.narration() + " — " + l.description() : l.narration(), blankZero(l.debit()),
                blankZero(l.credit()), l.balance(), l.balanceSide()));
        d.row(ReportDocument.Style.TOTAL, "", "", "Closing balance", gl.totalDebit(), gl.totalCredit(), gl.closingBalance(), gl.closingSide());
        return d;
    }

    private ReportDocument ageing(UUID clientId, ReceivablesService.Ageing a) {
        FinancialReportService.ReportHeader h = reports.trialBalance(clientId, a.asOf()).header();
        ReportDocument d = doc(a.kind() == ReceivablesService.Kind.RECEIVABLE ? "Receivables Ageing" : "Payables Ageing", h)
                .column(a.kind() == ReceivablesService.Kind.RECEIVABLE ? "Customer" : "Vendor", false, 46)
                .column("Outstanding", true, 20);
        a.bucketLabels().forEach(b -> d.column(b, true, 18));
        d.column("Not linked", true, 16).column("Advance", true, 16);
        for (ReceivablesService.AgeingRow r : a.rows()) {
            List<String> c = new ArrayList<>(List.of(r.partyName() + (r.gstin() != null ? " (" + r.gstin() + ")" : ""), r.outstanding()));
            r.buckets().forEach(x -> c.add(blankZero(x)));
            c.add(blankZero(r.unlinked()));
            c.add(blankZero(r.advance()));
            d.row(ReportDocument.Style.NORMAL, c);
        }
        List<String> t = new ArrayList<>(List.of("Total", a.total()));
        t.addAll(a.bucketTotals());
        t.add(a.unlinkedTotal());
        t.add(a.advanceTotal());
        d.row(ReportDocument.Style.TOTAL, t);
        d.note("Balances are allocated to invoices newest-first (payments assumed to settle the oldest invoices); days are counted from the due date.");
        d.note(a.reconciles() ? "Total agrees with the control ledger balance of ₹" + a.controlBalance() + "."
                : "WARNING: total does not agree with the control ledger (₹" + a.controlBalance() + ").");
        return d;
    }

    private ReportDocument register(UUID clientId, ReceivablesService.Register r) {
        FinancialReportService.ReportHeader h = reports.profitAndLoss(clientId, r.from(), r.to(), null, null).header();
        boolean sales = "SALES".equals(r.kind());
        ReportDocument d = doc(sales ? "Sales Register" : "Purchase Register", h)
                .column("Date", false, 14).column("Document", false, 22).column(sales ? "Customer" : "Vendor", false, 36)
                .column("GSTIN", false, 22).column("POS", false, 6).column("Taxable", true, 18).column("CGST", true, 14)
                .column("SGST", true, 14).column("IGST", true, 14).column("Total", true, 18);
        r.rows().forEach(x -> d.row(ReportDocument.Style.NORMAL, D.format(x.date()),
                x.number() + (x.documentType().endsWith("_NOTE") ? " (" + (x.documentType().startsWith("CREDIT") ? "CN" : "DN") + ")" : ""),
                x.partyName() + (x.reverseCharge() ? " [RCM]" : ""), x.partyGstin() == null ? "Unregistered" : x.partyGstin(),
                x.placeOfSupply(), x.taxable(), blankZero(x.cgst()), blankZero(x.sgst()), blankZero(x.igst()), x.total()));
        d.row(ReportDocument.Style.TOTAL, "", "", "Total", "", "", r.taxable(), r.cgst(), r.sgst(), r.igst(), r.total());
        d.note(sales ? "Credit notes are shown as negative amounts." : "Debit notes are shown as negative amounts.");
        return d;
    }

    // ---------------------------------------------------------------- helpers

    private static String[] cells(boolean cmp, String label, String amount, String previous) {
        return cmp ? new String[]{label, amount == null ? "" : amount, previous == null ? "" : previous}
                : new String[]{label, amount == null ? "" : amount};
    }

    private static String blankZero(String v) {
        return v == null || v.equals("0.00") ? "" : v;
    }

    private String me() {
        CurrentUser u = CurrentUser.require();
        return users.findById(u.id()).map(x -> x.getFullName()).orElse(u.email());
    }

    private static LocalDate need(LocalDate d, String name) {
        if (d == null) throw ApiException.badRequest("MISSING_PARAMETER", "Parameter '" + name + "' is required for this report");
        return d;
    }

    private static UUID needId(UUID id) {
        if (id == null) throw ApiException.badRequest("MISSING_PARAMETER", "Parameter 'accountId' is required for the ledger");
        return id;
    }
}
