package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationsExceptionHandlerTest {

    @Mock MessageSource messageSource;

    private NotificationsExceptionHandler handler;

    @BeforeEach
    void setUp() {
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        handler = new NotificationsExceptionHandler(messageSource);
    }

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
        assertThat(pd.getDetail()).isEqualTo("error.notification_channel_config_invalid");
    }

    @Test
    void deliveryMapsTo502() {
        var pd = handler.handleDelivery(new NotificationDeliveryException("upstream"));
        assertThat(pd.getStatus()).isEqualTo(502);
        assertThat(pd.getProperties()).containsEntry("error", "NOTIFICATION_DELIVERY_FAILED");
        assertThat(pd.getDetail()).isEqualTo("error.notification_delivery_failed");
    }
}
