package com.bablsoft.accessflow.scheduling.internal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobMonitoringPropertiesTest {

    @Test
    void defaultsApplyWhenNothingIsSet() {
        var properties = new JobMonitoringProperties(null, null, null, null, null);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.maxPerJob()).isEqualTo(500);
        assertThat(properties.retentionPollInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.summaryWindow()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void nonPositiveSummaryWindowFallsBackToTheDefault() {
        assertThat(new JobMonitoringProperties(true, null, null, null, Duration.ZERO).summaryWindow())
                .isEqualTo(Duration.ofHours(24));
        assertThat(new JobMonitoringProperties(true, null, null, null, Duration.ofHours(-1)).summaryWindow())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void bindsEveryKnob() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.scheduling.executions.enabled", "false",
                "accessflow.scheduling.executions.retention", "P3D",
                "accessflow.scheduling.executions.max-per-job", "50",
                "accessflow.scheduling.executions.retention-poll-interval", "PT1H",
                "accessflow.scheduling.executions.summary-window", "PT12H"));

        var properties = new Binder(source)
                .bind("accessflow.scheduling.executions", JobMonitoringProperties.class).get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(3));
        assertThat(properties.maxPerJob()).isEqualTo(50);
        assertThat(properties.retentionPollInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.summaryWindow()).isEqualTo(Duration.ofHours(12));
    }
}
