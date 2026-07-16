package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.proxy.internal.ProxyPoolProperties.Execution;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

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
        assertThat(props.execution().insertBatchChunkSize()).isEqualTo(1_000);
    }

    /**
     * Regression guard: record constructor-binding silently degrades to all-defaults when the
     * record grows a second constructor — this binds through Spring's Binder exactly like
     * application.yml does, so an override that stops binding fails here.
     */
    @Test
    void bindsNestedExecutionOverridesThroughTheBinder() {
        var source = new MapConfigurationPropertySource(Map.of(
                "accessflow.proxy.execution.statement-timeout", "3s",
                "accessflow.proxy.execution.max-rows", "500",
                "accessflow.proxy.execution.insert-batch-chunk-size", "42",
                "accessflow.proxy.cache.default-ttl", "PT2M",
                "accessflow.proxy.replica.cooldown", "PT10S"));
        var binder = new Binder(source);

        var pool = binder.bind("accessflow.proxy", ProxyPoolProperties.class).get();
        assertThat(pool.execution().statementTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(pool.execution().maxRows()).isEqualTo(500);
        assertThat(pool.execution().insertBatchChunkSize()).isEqualTo(42);

        var cache = binder.bind("accessflow.proxy.cache", ProxyCacheProperties.class).get();
        assertThat(cache.defaultTtl()).isEqualTo(Duration.ofMinutes(2));

        var replica = binder.bind("accessflow.proxy.replica", ProxyReplicaProperties.class).get();
        assertThat(replica.cooldown()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void explicitValuesPassThroughUnchanged() {
        var props = new ProxyPoolProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofMinutes(15),
                Duration.ofSeconds(2), "custom-",
                new Execution(500, Duration.ofSeconds(7), 250, 100, 1_048_576L, 8,
                        Duration.ofSeconds(2)));

        assertThat(props.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.maxLifetime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.leakDetectionThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.poolNamePrefix()).isEqualTo("custom-");
        assertThat(props.execution().maxRows()).isEqualTo(500);
        assertThat(props.execution().statementTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.execution().defaultFetchSize()).isEqualTo(250);
        assertThat(props.execution().maxResultBytes()).isEqualTo(1_048_576L);
        assertThat(props.execution().maxConcurrent()).isEqualTo(8);
        assertThat(props.execution().acquireTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void executionRecordYieldsDefaultsForNullFields() {
        var execution = new Execution(null, null, null, null, null, null, null);

        assertThat(execution.maxRows()).isEqualTo(10_000);
        assertThat(execution.statementTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(execution.defaultFetchSize()).isEqualTo(1_000);
        assertThat(execution.insertBatchChunkSize()).isEqualTo(1_000);
        assertThat(execution.maxResultBytes()).isEqualTo(52_428_800L);
        assertThat(execution.maxConcurrent()).isEqualTo(32);
        assertThat(execution.acquireTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
