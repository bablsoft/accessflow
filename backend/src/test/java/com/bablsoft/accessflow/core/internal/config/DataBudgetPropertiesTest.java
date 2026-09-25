package com.bablsoft.accessflow.core.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DataBudgetPropertiesTest {

    @Test
    void defaultsAndFloorsTheRetention() {
        assertThat(new DataBudgetProperties(null).usageRetention())
                .isEqualTo(DataBudgetProperties.DEFAULT_RETENTION);
        assertThat(new DataBudgetProperties(Duration.ofDays(1)).usageRetention())
                .isEqualTo(DataBudgetProperties.MIN_RETENTION);
        assertThat(new DataBudgetProperties(Duration.ofDays(90)).usageRetention())
                .isEqualTo(Duration.ofDays(90));
    }
}
