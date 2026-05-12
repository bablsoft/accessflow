package com.bablsoft.accessflow.notifications.internal.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestNotificationChannelRequestTest {

    @Test
    void recordExposesEmail() {
        var req = new TestNotificationChannelRequest("ops@example.com");
        assertThat(req.email()).isEqualTo("ops@example.com");
    }

    @Test
    void nullEmailIsAllowed() {
        var req = new TestNotificationChannelRequest(null);
        assertThat(req.email()).isNull();
    }
}
