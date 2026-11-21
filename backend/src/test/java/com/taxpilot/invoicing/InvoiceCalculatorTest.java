package com.taxpilot.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class InvoiceCalculatorTest {

    @Test
    void intraStateSplitsTaxEqually() {
        InvoiceCalculator.LineResult r = InvoiceCalculator.line(new BigDecimal("10"), new BigDecimal("1000.00"),
                new BigDecimal("18"), Invoice.SupplyType.INTRA);
        assertThat(r.taxable()).isEqualByComparingTo("10000.00");
        assertThat(r.cgst()).isEqualByComparingTo("900.00");
        assertThat(r.sgst()).isEqualByComparingTo("900.00");
        assertThat(r.igst()).isEqualByComparingTo("0.00");
    }

    @Test
    void interStateChargesIgst() {
        InvoiceCalculator.LineResult r = InvoiceCalculator.line(new BigDecimal("1"), new BigDecimal("2500.00"),
                new BigDecimal("5"), Invoice.SupplyType.INTER);
        assertThat(r.igst()).isEqualByComparingTo("125.00");
        assertThat(r.cgst()).isEqualByComparingTo("0.00");
    }

    @Test
    void roundsHalfUpPerLineToPaise() {
        // 3 x 33.33 = 99.99; 18% intra → 9% of 99.99 = 8.9991 → 9.00 each
        InvoiceCalculator.LineResult r = InvoiceCalculator.line(new BigDecimal("3"), new BigDecimal("33.33"),
                new BigDecimal("18"), Invoice.SupplyType.INTRA);
        assertThat(r.taxable()).isEqualByComparingTo("99.99");
        assertThat(r.cgst()).isEqualByComparingTo("9.00");
        // fractional quantity: 2.5 x 10.01 = 25.025 → 25.03
        assertThat(InvoiceCalculator.line(new BigDecimal("2.5"), new BigDecimal("10.01"), BigDecimal.ZERO,
                Invoice.SupplyType.INTRA).taxable()).isEqualByComparingTo("25.03");
    }

    @Test
    void totalsSumTheLines() {
        InvoiceCalculator.Totals t = InvoiceCalculator.totals(List.of(
                InvoiceCalculator.line(BigDecimal.ONE, new BigDecimal("200000"), new BigDecimal("18"), Invoice.SupplyType.INTRA),
                InvoiceCalculator.line(BigDecimal.ONE, new BigDecimal("1000"), BigDecimal.ZERO, Invoice.SupplyType.INTRA)));
        assertThat(t.taxable()).isEqualByComparingTo("201000.00");
        assertThat(t.cgst()).isEqualByComparingTo("18000.00");
        assertThat(t.grand()).isEqualByComparingTo("237000.00");
    }

    @Test
    void invoiceNumbersStayWithinTheGstSixteenCharacterLimit() {
        assertThat(InvoiceCalculator.formatNumber("INV", "2026-27", 1)).isEqualTo("INV/26-27/00001");
        String longest = InvoiceCalculator.formatNumber("ABCD", "2026-27", 99999);
        assertThat(longest).isEqualTo("ABCD/26-27/99999").hasSizeLessThanOrEqualTo(16);
        assertThatThrownBy(() -> InvoiceCalculator.formatNumber("ABCDE", "2026-27", 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
