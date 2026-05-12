package com.bablsoft.accessflow.workflow.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPropertiesTest {

    @Test
    void usesProvidedTimeoutPollInterval() {
        var props = new WorkflowProperties(Duration.ofMinutes(2));
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void defaultsToFiveMinutesWhenNullProvided() {
        var props = new WorkflowProperties(null);
        assertThat(props.timeoutPollInterval()).isEqualTo(Duration.ofMinutes(5));
    }
}
