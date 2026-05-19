package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.DiscordChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
class DiscordNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final DiscordPayloadFactory payloadFactory;
    private final RestClient restClient;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.DISCORD;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeDiscord(channel.getConfigJson());
        var body = payloadFactory.buildEventBody(ctx, config);
        post(config, body, "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeDiscord(channel.getConfigJson());
        var body = payloadFactory.buildTestBody(config);
        post(config, body, "test");
    }

    private void post(DiscordChannelConfig config, String body, String description) {
        try {
            restClient.post()
                    .uri(config.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted Discord {} to {}", description, config.webhookUrl());
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "Discord delivery returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("Discord delivery failed", ex);
        }
    }
}
