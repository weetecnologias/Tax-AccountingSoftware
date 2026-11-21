package com.taxpilot.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IndianIdsTest {

    @Test
    void validatesPanFormat() {
        assertThat(IndianIds.isValidPan("AAAPL1234C")).isTrue();
        assertThat(IndianIds.isValidPan("AAAPL1234")).isFalse();
        assertThat(IndianIds.isValidPan("aaapl1234c")).isFalse();
        assertThat(IndianIds.isValidPan("AAAP11234C")).isFalse();
        assertThat(IndianIds.isValidPan(null)).isFalse();
        assertThat(IndianIds.panHolderType("AAACX1234C")).isEqualTo('C');
    }

    @Test
    void validatesTan() {
        assertThat(IndianIds.isValidTan("MUMA12345B")).isTrue();
        assertThat(IndianIds.isValidTan("MUM12345B")).isFalse();
    }

    @Test
    void computesGstinCheckDigit() {
        // Widely published sample GSTIN with a valid check character.
        assertThat(IndianIds.gstinCheckChar("27AAPFU0939F1Z")).isEqualTo('V');
        assertThat(IndianIds.isValidGstin("27AAPFU0939F1ZV")).isTrue();
    }

    @Test
    void rejectsGstinWithBadChecksumStateOrShape() {
        assertThat(IndianIds.isValidGstin("27AAPFU0939F1ZA")).isFalse(); // wrong check digit
        assertThat(IndianIds.isValidGstin("28AAPFU0939F1ZV")).isFalse(); // 28 is not a current state code
        assertThat(IndianIds.isValidGstin("27AAPFU0939F1V")).isFalse();  // too short
        assertThat(IndianIds.isValidGstin("27AAPFU0939F1XV")).isFalse(); // 14th char must be Z
    }

    @Test
    void extractsPanFromGstin() {
        assertThat(IndianIds.panFromGstin("27AAPFU0939F1ZV")).isEqualTo("AAPFU0939F");
    }

    @Test
    void validatesBankingAndPostalFields() {
        assertThat(IndianIds.isValidIfsc("HDFC0001234")).isTrue();
        assertThat(IndianIds.isValidIfsc("HDFC1001234")).isFalse();
        assertThat(IndianIds.isValidPincode("560001")).isTrue();
        assertThat(IndianIds.isValidPincode("060001")).isFalse();
        assertThat(IndianIds.isValidAccountNumber("123456789012")).isTrue();
        assertThat(IndianIds.isValidAccountNumber("12AB")).isFalse();
    }

    @Test
    void masksAllButLastFour() {
        assertThat(IndianIds.mask("234F", 10)).isEqualTo("******234F");
    }
}
