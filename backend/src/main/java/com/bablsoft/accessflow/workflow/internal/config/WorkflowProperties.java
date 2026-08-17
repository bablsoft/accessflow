package com.bablsoft.accessflow.workflow.internal.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties("accessflow.workflow")
@Validated
public record WorkflowProperties(@NotNull Duration timeoutPollInterval,
                                 @NotNull Duration scheduledRunPollInterval,
                                 @NotNull Duration recurringRunPollInterval,
                                 @NotNull Duration recurrenceMinInterval,
                                 @NotNull Duration escalationPollInterval) {

    public WorkflowProperties {
        if (timeoutPollInterval == null) {
            timeoutPollInterval = Duration.ofMinutes(5);
        }
        if (scheduledRunPollInterval == null) {
            scheduledRunPollInterval = Duration.ofMinutes(1);
        }
        if (recurringRunPollInterval == null) {
            recurringRunPollInterval = Duration.ofMinutes(1);
        }
        if (recurrenceMinInterval == null) {
            recurrenceMinInterval = Duration.ofMinutes(5);
        }
        if (escalationPollInterval == null) {
            escalationPollInterval = Duration.ofMinutes(5);
        }
    }
}
