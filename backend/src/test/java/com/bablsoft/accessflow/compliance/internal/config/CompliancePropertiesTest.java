package com.bablsoft.accessflow.compliance.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CompliancePropertiesTest {

    @Test
    void appliesDefaultsForNullOrNonPositiveValues() {
        var props = new ComplianceProperties(null, 0);

        assertThat(props.maxReportPeriod()).isEqualTo(Duration.ofDays(366));
        assertThat(props.maxRows()).isEqualTo(50_000);
    }

    @Test
    void defaultsNegativeOrZeroPeriod() {
        assertThat(new ComplianceProperties(Duration.ZERO, 10).maxReportPeriod())
                .isEqualTo(Duration.ofDays(366));
        assertThat(new ComplianceProperties(Duration.ofDays(-1), 10).maxReportPeriod())
                .isEqualTo(Duration.ofDays(366));
    }

    @Test
    void keepsExplicitValues() {
        var props = new ComplianceProperties(Duration.ofDays(90), 1000);

        assertThat(props.maxReportPeriod()).isEqualTo(Duration.ofDays(90));
        assertThat(props.maxRows()).isEqualTo(1000);
    }
}
