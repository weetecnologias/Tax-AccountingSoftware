package com.taxpilot.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Format and checksum validation for Indian tax and banking identifiers. */
public final class IndianIds {

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern TAN = Pattern.compile("^[A-Z]{4}[0-9]{5}[A-Z]$");
    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{6,18}$");
    private static final String GSTIN_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private IndianIds() {
    }

    public static String normalise(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValidPan(String pan) {
        return pan != null && PAN.matcher(pan).matches();
    }

    /** 4th character of a PAN identifies the holder type (P, C, H, F, A, T, B, L, J, G). */
    public static char panHolderType(String pan) {
        return pan.charAt(3);
    }

    public static boolean isValidTan(String tan) {
        return tan != null && TAN.matcher(tan).matches();
    }

    public static boolean isValidIfsc(String ifsc) {
        return ifsc != null && IFSC.matcher(ifsc).matches();
    }

    public static boolean isValidPincode(String pin) {
        return pin != null && PINCODE.matcher(pin).matches();
    }

    public static boolean isValidAccountNumber(String number) {
        return number != null && ACCOUNT_NUMBER.matcher(number).matches();
    }

    /** Validates structure, state code and the GSTIN check digit (mod-36 Luhn variant). */
    public static boolean isValidGstin(String gstin) {
        if (gstin == null || !GSTIN.matcher(gstin).matches()) {
            return false;
        }
        if (!IndianStates.isValid(gstin.substring(0, 2))) {
            return false;
        }
        return gstinCheckChar(gstin.substring(0, 14)) == gstin.charAt(14);
    }

    public static String panFromGstin(String gstin) {
        return gstin.substring(2, 12);
    }

    static char gstinCheckChar(String first14) {
        int mod = GSTIN_CHARSET.length();
        int sum = 0;
        for (int i = 0; i < first14.length(); i++) {
            int value = GSTIN_CHARSET.indexOf(first14.charAt(i));
            int factor = (i % 2 == 0) ? 1 : 2;
            int product = value * factor;
            sum += (product / mod) + (product % mod);
        }
        int check = (mod - (sum % mod)) % mod;
        return GSTIN_CHARSET.charAt(check);
    }

    /** Masks all but the last four characters, e.g. ******234F. */
    public static String mask(String last4, int totalLength) {
        return "*".repeat(Math.max(totalLength - last4.length(), 0)) + last4;
    }
}
