package com.taxpilot.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taxpilot.accounting.Money;
import com.taxpilot.clients.ClientService;
import com.taxpilot.common.ApiException;

/**
 * Receivable / payable ageing and sales / purchase registers, computed from posted data.
 *
 * Ageing: each party's balance on the control ledger (Trade Receivables or Trade Payables) is allocated to its posted
 * invoices newest-first (i.e. payments are assumed to settle the oldest invoices first) and aged by days past due.
 * Balances that cannot be traced to an invoice are shown as "not linked to an invoice"; control-ledger entries without
 * a party are shown on a separate "unassigned" row.
 */
@Service
@Transactional(readOnly = true)
public class ReceivablesService {

    public enum Kind { RECEIVABLE, PAYABLE }

    public static final List<String> BUCKETS = List.of("Current", "1-30 days", "31-60 days", "61-90 days", "Above 90 days");

    public record AgeingRow(UUID partyId, String partyName, String gstin, String outstanding, List<String> buckets,
                            String unlinked, String advance) {
    }

    public record Ageing(Kind kind, LocalDate asOf, List<String> bucketLabels, List<AgeingRow> rows, String total,
                         List<String> bucketTotals, String unlinkedTotal, String advanceTotal, String controlBalance,
                         boolean reconciles) {
    }

    public record RegisterRow(UUID invoiceId, String documentType, String number, LocalDate date, String partyName,
                              String partyGstin, String placeOfSupply, String supplyType, boolean reverseCharge,
                              String taxable, String cgst, String sgst, String igst, String total) {
    }

    public record Register(String kind, LocalDate from, LocalDate to, List<RegisterRow> rows, String taxable,
                           String cgst, String sgst, String igst, String total) {
    }

    private final JdbcTemplate jdbc;
    private final ClientService clients;

    public ReceivablesService(JdbcTemplate jdbc, ClientService clients) {
        this.jdbc = jdbc;
        this.clients = clients;
    }

    public Ageing ageing(UUID clientId, Kind kind, LocalDate asOf) {
        clients.find(clientId);
        String control = kind == Kind.RECEIVABLE ? "IN-STANDARD:1130" : "IN-STANDARD:2110";
        String subtype = kind == Kind.RECEIVABLE ? "RECEIVABLE" : "PAYABLE";
        // Normal-side balance per party: receivables are debit balances, payables credit balances.
        String sign = kind == Kind.RECEIVABLE ? "l.debit - l.credit" : "l.credit - l.debit";
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        BigDecimal[] unassigned = {Money.ZERO};
        jdbc.query("""
                select l.party_id, coalesce(sum(%s), 0) as bal
                from journal_entry_lines l
                join journal_entries e on e.id = l.journal_entry_id
                join ledger_accounts a on a.id = l.account_id
                where a.client_id = ? and (a.template_code = ? or a.subtype = ?) and e.status = 'POSTED' and e.entry_date <= ?
                group by l.party_id
                """.formatted(sign), rs -> {
            UUID p = rs.getObject("party_id", UUID.class);
            BigDecimal bal = Money.of(rs.getBigDecimal("bal"));
            if (p == null) unassigned[0] = unassigned[0].add(bal);
            else balances.put(p, bal);
        }, clientId, control, subtype, asOf);

        String docType = kind == Kind.RECEIVABLE ? "SALES" : "PURCHASE";
        List<AgeingRow> rows = new ArrayList<>();
        BigDecimal[] bucketTotals = {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO};
        BigDecimal total = Money.ZERO;
        BigDecimal unlinkedTotal = Money.ZERO;
        BigDecimal advanceTotal = Money.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : balances.entrySet()) {
            BigDecimal bal = e.getValue();
            if (bal.signum() == 0) continue;
            Map<String, Object> party = jdbc.queryForMap("select name, gstin from parties where id = ?", e.getKey());
            BigDecimal[] b = {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO};
            BigDecimal unlinked = Money.ZERO;
            BigDecimal advance = Money.ZERO;
            if (bal.signum() < 0) {
                advance = bal.negate();
            } else {
                BigDecimal remaining = bal;
                for (Map<String, Object> inv : jdbc.queryForList("""
                        select grand_total, coalesce(due_date, invoice_date) as due from invoices
                        where client_id = ? and party_id = ? and invoice_type = ? and status = 'POSTED' and invoice_date <= ?
                        order by invoice_date desc, created_at desc
                        """, clientId, e.getKey(), docType, asOf)) {
                    if (remaining.signum() <= 0) break;
                    BigDecimal amount = Money.of((BigDecimal) inv.get("grand_total")).min(remaining);
                    LocalDate due = ((java.sql.Date) inv.get("due")).toLocalDate();
                    int idx = bucket(ChronoUnit.DAYS.between(due, asOf));
                    b[idx] = b[idx].add(amount);
                    remaining = remaining.subtract(amount);
                }
                unlinked = remaining.max(Money.ZERO);
            }
            for (int i = 0; i < 5; i++) bucketTotals[i] = bucketTotals[i].add(b[i]);
            total = total.add(bal);
            unlinkedTotal = unlinkedTotal.add(unlinked);
            advanceTotal = advanceTotal.add(advance);
            rows.add(new AgeingRow(e.getKey(), (String) party.get("name"), (String) party.get("gstin"), Money.str(bal),
                    List.of(Money.str(b[0]), Money.str(b[1]), Money.str(b[2]), Money.str(b[3]), Money.str(b[4])),
                    Money.str(unlinked), Money.str(advance)));
        }
        rows.sort((x, y) -> new BigDecimal(y.outstanding()).compareTo(new BigDecimal(x.outstanding())));
        if (unassigned[0].signum() != 0) {
            rows.add(new AgeingRow(null, "Unassigned (no customer/vendor on the entry)", null, Money.str(unassigned[0]),
                    List.of("0.00", "0.00", "0.00", "0.00", "0.00"), Money.str(unassigned[0].max(Money.ZERO)),
                    Money.str(unassigned[0].min(Money.ZERO).negate())));
            total = total.add(unassigned[0]);
            unlinkedTotal = unlinkedTotal.add(unassigned[0].max(Money.ZERO));
            advanceTotal = advanceTotal.add(unassigned[0].min(Money.ZERO).negate());
        }
        BigDecimal controlBalance = Money.of(jdbc.queryForObject("""
                select coalesce(sum(%s), 0) from journal_entry_lines l
                join journal_entries e on e.id = l.journal_entry_id join ledger_accounts a on a.id = l.account_id
                where a.client_id = ? and (a.template_code = ? or a.subtype = ?) and e.status = 'POSTED' and e.entry_date <= ?
                """.formatted(sign), BigDecimal.class, clientId, control, subtype, asOf));
        BigDecimal check = Money.ZERO;
        for (BigDecimal x : bucketTotals) check = check.add(x);
        check = check.add(unlinkedTotal).subtract(advanceTotal);
        return new Ageing(kind, asOf, BUCKETS, rows, Money.str(total),
                List.of(Money.str(bucketTotals[0]), Money.str(bucketTotals[1]), Money.str(bucketTotals[2]),
                        Money.str(bucketTotals[3]), Money.str(bucketTotals[4])),
                Money.str(unlinkedTotal), Money.str(advanceTotal), Money.str(controlBalance),
                check.compareTo(controlBalance) == 0 && total.compareTo(controlBalance) == 0);
    }

    static int bucket(long daysPastDue) {
        if (daysPastDue <= 0) return 0;
        if (daysPastDue <= 30) return 1;
        if (daysPastDue <= 60) return 2;
        if (daysPastDue <= 90) return 3;
        return 4;
    }

    /** Sales register: posted sales invoices (+) and credit notes (−). Purchase register: purchases (+) and debit notes (−). */
    public Register register(UUID clientId, String kind, LocalDate from, LocalDate to) {
        clients.find(clientId);
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("BAD_RANGE", "Choose a valid date range");
        boolean sales = "SALES".equals(kind);
        if (!sales && !"PURCHASE".equals(kind)) throw ApiException.badRequest("BAD_KIND", "kind must be SALES or PURCHASE");
        List<RegisterRow> rows = new ArrayList<>();
        BigDecimal[] t = {Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO};
        for (Map<String, Object> r : jdbc.queryForList("""
                select i.id, i.invoice_type, coalesce(i.invoice_number, i.supplier_invoice_number) as num, i.invoice_date,
                       p.name, p.gstin, i.place_of_supply, i.supply_type, i.reverse_charge,
                       i.taxable_total, i.cgst_total, i.sgst_total, i.igst_total, i.grand_total
                from invoices i join parties p on p.id = i.party_id
                where i.client_id = ? and i.status = 'POSTED' and i.invoice_type in (?, ?) and i.invoice_date between ? and ?
                order by i.invoice_date, num
                """, clientId, sales ? "SALES" : "PURCHASE", sales ? "CREDIT_NOTE" : "DEBIT_NOTE", from, to)) {
            boolean note = ((String) r.get("invoice_type")).endsWith("_NOTE");
            BigDecimal[] v = new BigDecimal[5];
            String[] cols = {"taxable_total", "cgst_total", "sgst_total", "igst_total", "grand_total"};
            for (int i = 0; i < 5; i++) {
                v[i] = Money.of((BigDecimal) r.get(cols[i]));
                if (note) v[i] = v[i].negate();
                t[i] = t[i].add(v[i]);
            }
            rows.add(new RegisterRow((UUID) r.get("id"), (String) r.get("invoice_type"), (String) r.get("num"),
                    ((java.sql.Date) r.get("invoice_date")).toLocalDate(), (String) r.get("name"), (String) r.get("gstin"),
                    (String) r.get("place_of_supply"), (String) r.get("supply_type"), (Boolean) r.get("reverse_charge"),
                    Money.str(v[0]), Money.str(v[1]), Money.str(v[2]), Money.str(v[3]), Money.str(v[4])));
        }
        return new Register(kind, from, to, rows, Money.str(t[0]), Money.str(t[1]), Money.str(t[2]), Money.str(t[3]),
                Money.str(t[4]));
    }

    @RestController
    @RequestMapping("/api/v1/accounting")
    public static class Controller {
        private final ReceivablesService service;

        public Controller(ReceivablesService service) {
            this.service = service;
        }

        @GetMapping("/ageing")
        @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
        public Ageing ageing(@RequestParam UUID clientId, @RequestParam Kind kind,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
            return service.ageing(clientId, kind, asOf);
        }

        @GetMapping("/registers")
        @PreAuthorize("hasAuthority('ACCOUNTING_READ')")
        public Register register(@RequestParam UUID clientId, @RequestParam String kind,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
            return service.register(clientId, kind, from, to);
        }
    }
}
