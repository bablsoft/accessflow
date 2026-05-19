package com.bablsoft.accessflow.audit.events;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDeliveryExhaustedEventTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @Test
    void acceptsValidPayload() {
        var event = new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "WEBHOOK", "QUERY_APPROVED", 4, 503, "boom");

        assertThat(event.organizationId()).isEqualTo(orgId);
        assertThat(event.channelId()).isEqualTo(channelId);
        assertThat(event.channelType()).isEqualTo("WEBHOOK");
        assertThat(event.eventType()).isEqualTo("QUERY_APPROVED");
        assertThat(event.attemptCount()).isEqualTo(4);
    }

    @Test
    void acceptsNullableHttpStatusAndError() {
        var event = new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "WEBHOOK", "QUERY_SUBMITTED", 1, null, null);

        assertThat(event.lastHttpStatus()).isNull();
        assertThat(event.lastError()).isNull();
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                null, channelId, "WEBHOOK", "QUERY_APPROVED", 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void rejectsNullChannelId() {
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, null, "WEBHOOK", "QUERY_APPROVED", 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelId");
    }

    @Test
    void rejectsBlankChannelType() {
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "", "QUERY_APPROVED", 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelType");
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, channelId, null, "QUERY_APPROVED", 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelType");
    }

    @Test
    void rejectsBlankEventType() {
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "WEBHOOK", "", 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "WEBHOOK", null, 4, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void rejectsZeroOrNegativeAttemptCount() {
        assertThatThrownBy(() -> new NotificationDeliveryExhaustedEvent(
                orgId, channelId, "WEBHOOK", "QUERY_APPROVED", 0, 500, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptCount");
    }
}
