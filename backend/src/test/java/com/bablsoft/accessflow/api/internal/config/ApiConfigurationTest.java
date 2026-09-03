package com.bablsoft.accessflow.api.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConfigurationTest {

    private final ApiConfiguration configuration = new ApiConfiguration();

    @Test
    void buildsAConfiguredRestClient() {
        var client = configuration.updateCheckRestClient(
                new UpdateCheckProperties(null, null, null, Duration.ofSeconds(3)));

        assertThat(client).isNotNull();
    }

    @Test
    void executorRunsTasksOnVirtualThreads() throws Exception {
        var executor = configuration.updateCheckExecutor();
        var virtual = new AtomicBoolean();
        try (executor) {
            executor.submit(() -> virtual.set(Thread.currentThread().isVirtual())).get(5, TimeUnit.SECONDS);
        }

        assertThat(virtual).isTrue();
    }
}
