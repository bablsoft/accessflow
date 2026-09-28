package com.bablsoft.accessflow.workflow.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WorkflowProperties.class, QuerySuggestionProperties.class,
        DecisionHookProperties.class})
class WorkflowConfiguration {

    /** Runs on-demand suggestion recomputes off the request thread (virtual threads, #776). */
    @Bean(destroyMethod = "close")
    ExecutorService querySuggestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * The decision hook's HTTP client (#945). Redirects are never followed: a 3xx is a failure, so
     * an endpoint that passed the address check cannot bounce the call to one that would not.
     * The per-hook timeout is applied per call, on top of this connect ceiling.
     */
    @Bean
    HttpClient decisionHookHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Bounds each decision hook call with an overall deadline (virtual threads, #945). */
    @Bean(destroyMethod = "close")
    ExecutorService decisionHookExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
