package com.bablsoft.accessflow.workflow.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WorkflowProperties.class, QuerySuggestionProperties.class})
class WorkflowConfiguration {

    /** Runs on-demand suggestion recomputes off the request thread (virtual threads, #776). */
    @Bean(destroyMethod = "close")
    ExecutorService querySuggestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
