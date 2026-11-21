package com.taxpilot.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.taxpilot.accounting.Money;

/**
 * Deterministic GST arithmetic for invoice lines. Taxable value = quantity x unit price rounded half-up to paise.
 * Intra-state: CGST and SGST each at half the rate; inter-state: IGST at the full rate; each rounded half-up per line.
 * Invoice totals are the sum of line values (no invoice-level round-off).
 */
public final class InvoiceCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO_HUNDRED = new BigDecimal("200");

    private InvoiceCalculator() {
    }

    public record LineResult(BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxable, BigDecimal rate,
                             BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        public BigDecimal tax() {
            return cgst.add(sgst).add(igst);
        }
    }

    public record Totals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal grand) {
    }

    public static LineResult line(BigDecimal quantity, BigDecimal unitPrice, BigDecimal rate, Invoice.SupplyType supply) {
        BigDecimal qty = quantity.setScale(3, RoundingMode.UNNECESSARY);
        BigDecimal taxable = qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cgst = Money.ZERO;
        BigDecimal sgst = Money.ZERO;
        BigDecimal igst = Money.ZERO;
        if (supply == Invoice.SupplyType.INTRA) {
            cgst = taxable.multiply(rate).divide(TWO_HUNDRED, 2, RoundingMode.HALF_UP);
            sgst = cgst;
        } else {
            igst = taxable.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return new LineResult(qty, Money.of(unitPrice), taxable, rate.setScale(2, RoundingMode.UNNECESSARY), cgst,
                sgst, igst);
    }

    public static Totals totals(List<LineResult> lines) {
        BigDecimal t = Money.ZERO;
        BigDecimal c = Money.ZERO;
        BigDecimal s = Money.ZERO;
        BigDecimal i = Money.ZERO;
        for (LineResult l : lines) {
            t = t.add(l.taxable());
            c = c.add(l.cgst());
            s = s.add(l.sgst());
            i = i.add(l.igst());
        }
        return new Totals(t, c, s, i, t.add(c).add(s).add(i));
    }

    /** "2026-27" → "INV/26-27/00001". Always ≤ 16 characters for a prefix of up to 4 characters. */
    public static String formatNumber(String prefix, String financialYear, int sequence) {
        String fy = financialYear.substring(2, 4) + "-" + financialYear.substring(5, 7);
        String number = prefix + "/" + fy + "/" + String.format("%05d", sequence);
        if (number.length() > 16) {
            throw new IllegalStateException("Invoice number exceeds the 16-character GST limit: " + number);
        }
        return number;
    }
}
