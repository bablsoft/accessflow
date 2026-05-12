package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.api.UserNotificationView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserNotificationResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPayloadJsonIntoTree() {
        var view = view("{\"datasource\":\"prod\",\"submitter\":\"a@x\"}");

        var resp = UserNotificationResponse.from(view, mapper);

        assertThat(resp.payload().get("datasource").asString()).isEqualTo("prod");
        assertThat(resp.payload().get("submitter").asString()).isEqualTo("a@x");
        assertThat(resp.eventType()).isEqualTo(NotificationEventType.QUERY_APPROVED);
        assertThat(resp.read()).isFalse();
    }

    @Test
    void blankPayloadBecomesEmptyObject() {
        var view = view("");

        var resp = UserNotificationResponse.from(view, mapper);

        assertThat(resp.payload().isObject()).isTrue();
        assertThat(resp.payload().size()).isZero();
    }

    @Test
    void invalidJsonFallsBackToEmptyObject() {
        var view = view("{not-json");

        var resp = UserNotificationResponse.from(view, mapper);

        assertThat(resp.payload().isObject()).isTrue();
        assertThat(resp.payload().size()).isZero();
    }

    private UserNotificationView view(String payloadJson) {
        return new UserNotificationView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationEventType.QUERY_APPROVED,
                UUID.randomUUID(),
                payloadJson,
                false,
                Instant.parse("2026-05-08T10:00:00Z"),
                null);
    }
}
