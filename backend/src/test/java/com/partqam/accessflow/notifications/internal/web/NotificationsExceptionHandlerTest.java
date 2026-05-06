package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationsExceptionHandlerTest {

    private final NotificationsExceptionHandler handler = new NotificationsExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        var pd = handler.handleNotFound(
                new NotificationChannelNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("error", "NOTIFICATION_CHANNEL_NOT_FOUND");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    void configMapsTo422() {
        var pd = handler.handleConfig(new NotificationChannelConfigException("bad"));
        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getProperties()).containsEntry("error", "NOTIFICATION_CHANNEL_CONFIG_INVALID");
        assertThat(pd.getDetail()).isEqualTo("bad");
    }

    @Test
    void deliveryMapsTo502() {
        var pd = handler.handleDelivery(new NotificationDeliveryException("upstream"));
        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("error", "NOTIFICATION_DELIVERY_FAILED");
        assertThat(pd.getDetail()).isEqualTo("upstream");
    }
}
