package com.bablsoft.accessflow.notifications.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationExceptionsTest {

    @Test
    void notFoundExceptionExposesChannelIdAndMessage() {
        var id = UUID.randomUUID();
        var ex = new NotificationChannelNotFoundException(id);
        assertThat(ex.channelId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void configExceptionWithMessageOnly() {
        var ex = new NotificationChannelConfigException("missing key");
        assertThat(ex.getMessage()).isEqualTo("missing key");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void configExceptionWithMessageAndCause() {
        var cause = new IllegalStateException("boom");
        var ex = new NotificationChannelConfigException("bad config", cause);
        assertThat(ex.getMessage()).isEqualTo("bad config");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void deliveryExceptionWithMessageOnly() {
        var ex = new NotificationDeliveryException("502");
        assertThat(ex.getMessage()).isEqualTo("502");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void deliveryExceptionWithMessageAndCause() {
        var cause = new java.io.IOException("network");
        var ex = new NotificationDeliveryException("upstream", cause);
        assertThat(ex.getMessage()).isEqualTo("upstream");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void userNotificationNotFoundExposesIdAndMessage() {
        var id = UUID.randomUUID();
        var ex = new UserNotificationNotFoundException(id);
        assertThat(ex.notificationId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }
}
