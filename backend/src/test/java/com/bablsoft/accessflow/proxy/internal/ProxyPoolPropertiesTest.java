package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.internal.ProxyPoolProperties.Execution;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyPoolPropertiesTest {

    @Test
    void allNullArgumentsYieldDocumentedDefaults() {
        var props = new ProxyPoolProperties(null, null, null, null, null, null);

        assertThat(props.connectionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.leakDetectionThreshold()).isEqualTo(Duration.ZERO);
        assertThat(props.poolNamePrefix()).isEqualTo("accessflow-ds-");
        assertThat(props.execution().maxRows()).isEqualTo(10_000);
        assertThat(props.execution().statementTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.execution().defaultFetchSize()).isEqualTo(1_000);
    }

    @Test
    void explicitValuesPassThroughUnchanged() {
        var props = new ProxyPoolProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofMinutes(15),
                Duration.ofSeconds(2), "custom-",
                new Execution(500, Duration.ofSeconds(7), 250));

        assertThat(props.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.leakDetectionThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.poolNamePrefix()).isEqualTo("custom-");
        assertThat(props.execution().maxRows()).isEqualTo(500);
        assertThat(props.execution().statementTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.execution().defaultFetchSize()).isEqualTo(250);
    }

    @Test
    void executionRecordYieldsDefaultsForNullFields() {
        var execution = new Execution(null, null, null);

        assertThat(execution.maxRows()).isEqualTo(10_000);
        assertThat(execution.statementTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(execution.defaultFetchSize()).isEqualTo(1_000);
    }
}
