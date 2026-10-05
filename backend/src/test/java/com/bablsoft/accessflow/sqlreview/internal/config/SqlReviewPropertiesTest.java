package com.bablsoft.accessflow.sqlreview.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewPropertiesTest {

    @Test
    void defaultsAMissingOrNonPositiveTtlToOneMinute() {
        assertThat(new SqlReviewProperties(null).customRuleCacheTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new SqlReviewProperties(Duration.ZERO).customRuleCacheTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new SqlReviewProperties(Duration.ofSeconds(-5)).customRuleCacheTtl())
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(new SqlReviewProperties(Duration.ofSeconds(30)).customRuleCacheTtl())
                .isEqualTo(Duration.ofSeconds(30));
    }
}
