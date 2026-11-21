package com.taxpilot.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.taxpilot.settings.SettingsService;

class ReportingTest {

    @Test
    void amountsInWordsUseLakhAndCrore() {
        assertThat(AmountInWords.rupees(new BigDecimal("0.00"))).isEqualTo("Rupees Zero Only");
        assertThat(AmountInWords.rupees(new BigDecimal("11800.00"))).isEqualTo("Rupees Eleven Thousand Eight Hundred Only");
        assertThat(AmountInWords.rupees(new BigDecimal("49589.50")))
                .isEqualTo("Rupees Forty Nine Thousand Five Hundred Eighty Nine and Fifty Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("1234567.89")))
                .isEqualTo("Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Eighty Nine Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("125000000.00"))).isEqualTo("Rupees Twelve Crore Fifty Lakh Only");
        assertThat(AmountInWords.rupees(new BigDecimal("-15.00"))).isEqualTo("Minus Rupees Fifteen Only");
    }

    @Test
    void indianDigitGrouping() {
        assertThat(ReportDocument.inr("0.00")).isEqualTo("0.00");
        assertThat(ReportDocument.inr("999.50")).isEqualTo("999.50");
        assertThat(ReportDocument.inr("1000.00")).isEqualTo("1,000.00");
        assertThat(ReportDocument.inr("736000.00")).isEqualTo("7,36,000.00");
        assertThat(ReportDocument.inr("123456789.12")).isEqualTo("12,34,56,789.12");
        assertThat(ReportDocument.inr("-692132.00")).isEqualTo("-6,92,132.00");
        assertThat(ReportDocument.inr("")).isEmpty();
    }

    @Test
    void rendersPdfAndXlsx() {
        ReportDocument d = new ReportDocument("Trial Balance", "Acme (ACM001)", "As at 31 Mar 2027 · amounts in ₹",
                new SettingsService.Letterhead("Firm", null, "Bengaluru", null, null, null, null), "Tester", Instant.now())
                .column("Account", false, 40).column("Debit", true, 20)
                .row(ReportDocument.Style.NORMAL, "Cash", "1500.00")
                .row(ReportDocument.Style.TOTAL, "Total", "1500.00");
        assertThat(new String(ReportRenderer.pdf(d), 0, 5)).isEqualTo("%PDF-");
        assertThat(new String(ReportRenderer.xlsx(d), 0, 2)).isEqualTo("PK");
        assertThat(ReportRenderer.pdfSafe("amounts in ₹")).isEqualTo("amounts in Rs. ");
        assertThat(ReportRenderer.pdfSafe("Total (₹)")).isEqualTo("Total (Rs.)");
    }
}
