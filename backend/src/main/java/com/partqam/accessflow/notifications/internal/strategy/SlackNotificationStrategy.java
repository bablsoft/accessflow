package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.SlackChannelConfig;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
class SlackNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final SlackBlockKitFactory blockKitFactory;
    private final RestClient restClient;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.SLACK;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var body = blockKitFactory.buildEventBody(ctx, config.channel());
        post(config, body, "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var body = blockKitFactory.buildTestBody(config.channel());
        post(config, body, "test");
    }

    private void post(SlackChannelConfig config, String body, String description) {
        try {
            restClient.post()
                    .uri(config.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted Slack {} to {}", description, config.webhookUrl());
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "Slack delivery failed (" + ex.getStatusCode() + ")", ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("Slack delivery failed", ex);
        }
    }
}
