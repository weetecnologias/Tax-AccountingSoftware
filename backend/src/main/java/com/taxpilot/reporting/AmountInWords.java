package com.taxpilot.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupees and paise in words using the Indian system (thousand, lakh, crore). */
public final class AmountInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private AmountInWords() {
    }

    public static String rupees(BigDecimal amount) {
        BigDecimal v = amount.setScale(2, RoundingMode.UNNECESSARY).abs();
        long rupees = v.longValue();
        int paise = v.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = rupees == 0 ? "Zero" : words(rupees);
        String out = "Rupees " + words + (paise > 0 ? " and " + words(paise) + " Paise" : "") + " Only";
        return amount.signum() < 0 ? "Minus " + out : out;
    }

    static String words(long n) {
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000;
        n %= 10_000_000;
        long lakh = n / 100_000;
        n %= 100_000;
        long thousand = n / 1000;
        n %= 1000;
        long hundred = n / 100;
        n %= 100;
        if (crore > 0) sb.append(words(crore)).append(" Crore ");
        if (lakh > 0) sb.append(twoDigits((int) lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(twoDigits((int) thousand)).append(" Thousand ");
        if (hundred > 0) sb.append(ONES[(int) hundred]).append(" Hundred ");
        if (n > 0) sb.append(twoDigits((int) n));
        return sb.toString().trim();
    }

    private static String twoDigits(int n) {
        if (n < 20) return ONES[n];
        return (TENS[n / 10] + " " + ONES[n % 10]).trim();
    }
}
