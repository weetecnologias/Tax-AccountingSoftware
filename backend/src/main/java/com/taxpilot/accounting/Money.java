package com.taxpilot.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.taxpilot.common.ApiException;

/** Money rules: BigDecimal only, two decimal places, never negative on a journal line, never floating point. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX = new BigDecimal("999999999999999.99");

    private Money() {
    }

    /** Parses a user-supplied amount. Blank means zero. More than two decimals is rejected, never rounded. */
    public static BigDecimal parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return ZERO;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw ApiException.field(field, "Enter a valid amount");
        }
        if (value.signum() < 0) {
            throw ApiException.field(field, "Amounts cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw ApiException.field(field, "Amounts can have at most two decimal places");
        }
        if (value.compareTo(MAX) > 0) {
            throw ApiException.field(field, "Amount is too large");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal of(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static String str(BigDecimal value) {
        return of(value).toPlainString();
    }
}
