package com.taxpilot.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgeingBucketTest {

    @Test
    void daysPastDueMapToBuckets() {
        assertThat(ReceivablesService.bucket(-5)).isZero();
        assertThat(ReceivablesService.bucket(0)).isZero();
        assertThat(ReceivablesService.bucket(1)).isEqualTo(1);
        assertThat(ReceivablesService.bucket(30)).isEqualTo(1);
        assertThat(ReceivablesService.bucket(31)).isEqualTo(2);
        assertThat(ReceivablesService.bucket(60)).isEqualTo(2);
        assertThat(ReceivablesService.bucket(61)).isEqualTo(3);
        assertThat(ReceivablesService.bucket(90)).isEqualTo(3);
        assertThat(ReceivablesService.bucket(91)).isEqualTo(4);
    }
}
