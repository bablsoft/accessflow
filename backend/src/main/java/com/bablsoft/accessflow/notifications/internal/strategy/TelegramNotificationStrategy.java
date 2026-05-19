package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.TelegramChannelConfig;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;

@Component
@Slf4j
class TelegramNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final TelegramMessageFactory messageFactory;
    private final RestClient restClient;
    private final String baseUrl;

    TelegramNotificationStrategy(ChannelConfigCodec codec,
                                 TelegramMessageFactory messageFactory,
                                 RestClient restClient,
                                 NotificationsProperties properties) {
        this.codec = codec;
        this.messageFactory = messageFactory;
        this.restClient = restClient;
        var raw = properties.telegramApiBaseUrl().toString();
        this.baseUrl = raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.TELEGRAM;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeTelegram(channel.getConfigJson());
        var body = messageFactory.buildEventBody(ctx, config.chatId());
        post(config, body, "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeTelegram(channel.getConfigJson());
        var body = messageFactory.buildTestBody(config.chatId());
        post(config, body, "test");
    }

    private void post(TelegramChannelConfig config, String body, String description) {
        var uri = URI.create(baseUrl + "bot" + config.botTokenPlain() + "/sendMessage");
        try {
            restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted Telegram {} to chat {}", description, config.chatId());
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "Telegram delivery returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("Telegram delivery failed", ex);
        }
    }
}
