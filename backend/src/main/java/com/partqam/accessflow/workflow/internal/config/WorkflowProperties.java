package com.partqam.accessflow.workflow.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties("accessflow.workflow")
@Validated
public record WorkflowProperties(@NotNull Duration timeoutPollInterval) {

    public WorkflowProperties {
        if (timeoutPollInterval == null) {
            timeoutPollInterval = Duration.ofMinutes(5);
        }
    }
}
