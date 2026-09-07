package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor's whole reason to exist is that indexing the help corpus blocks for minutes against a
 * slow embedding backend. On platform threads a pool of that shape would pin carrier threads for the
 * duration; virtual-thread-per-task is the property worth asserting, and nothing else in the change
 * would fail if it silently became a fixed pool.
 */
class HelpAgentConfigurationTest {

    private final HelpAgentConfiguration configuration = new HelpAgentConfiguration();

    @Test
    void executorRunsTasksOnVirtualThreads() throws Exception {
        var executor = configuration.helpCorpusIndexExecutor();
        var virtual = new AtomicBoolean();
        try (executor) {
            executor.submit(() -> virtual.set(Thread.currentThread().isVirtual()))
                    .get(5, TimeUnit.SECONDS);
        }

        assertThat(virtual).isTrue();
    }

    @Test
    void executorIsNotShared() {
        var first = configuration.helpCorpusIndexExecutor();
        var second = configuration.helpCorpusIndexExecutor();
        try (first; second) {
            // A dedicated bean, so a multi-minute ingestion cannot starve anything else.
            assertThat(first).isNotSameAs(second);
        }
    }
}
