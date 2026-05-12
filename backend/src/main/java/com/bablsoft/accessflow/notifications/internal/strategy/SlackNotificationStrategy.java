package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.SlackChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.slack.api.Slack;
import com.slack.api.webhook.Payload;
import com.slack.api.webhook.WebhookResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
class SlackNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final SlackBlockKitFactory blockKitFactory;
    private final Slack slack;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.SLACK;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var payload = blockKitFactory.buildEventPayload(ctx, config.channel());
        post(config, payload, "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var payload = blockKitFactory.buildTestPayload(config.channel());
        post(config, payload, "test");
    }

    private void post(SlackChannelConfig config, Payload payload, String description) {
        WebhookResponse response;
        try {
            response = slack.send(config.webhookUrl().toString(), payload);
        } catch (IOException ex) {
            throw new NotificationDeliveryException("Slack delivery failed", ex);
        }
        if (response.getCode() == null || response.getCode() < 200 || response.getCode() >= 300) {
            throw new NotificationDeliveryException(
                    "Slack delivery returned HTTP " + response.getCode()
                            + (response.getBody() != null ? ": " + response.getBody() : ""));
        }
        log.debug("Posted Slack {} to {} ({})",
                description, config.webhookUrl(), response.getCode());
    }
}
