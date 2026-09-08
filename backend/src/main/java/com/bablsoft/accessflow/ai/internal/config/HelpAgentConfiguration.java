package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enables the {@link HelpAgentProperties} and {@link HelpCorpusProperties} bindings and supplies the
 * executor the help corpus is indexed on (AF-902, AF-907).
 *
 * <p>The executor is virtual-thread-per-task and dedicated: embedding ~510 chunks against a CPU-only
 * Ollama takes minutes, and that must never sit on the thread that publishes
 * {@code ApplicationReadyEvent} or on the one serving the admin re-index request. It is a distinct
 * bean rather than the common task executor so a long ingestion cannot starve anything else.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({HelpAgentProperties.class, HelpCorpusProperties.class})
class HelpAgentConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService helpCorpusIndexExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
