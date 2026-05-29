package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.DecryptedSlackApp;
import com.bablsoft.accessflow.notifications.internal.DefaultSlackAppConfigService;
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

/**
 * Delivers Slack notifications via one of two transports:
 *
 * <ul>
 *   <li><b>Bot token</b> ({@code chat.postMessage}) when the organization has a configured Slack
 *       app — this lets review-request messages carry interactive Approve / Reject buttons.</li>
 *   <li><b>Incoming webhook</b> (the original one-way path) when no Slack app is configured.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SlackNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final SlackBlockKitFactory blockKitFactory;
    private final SlackBotMessenger botMessenger;
    private final DefaultSlackAppConfigService appConfigService;
    private final Slack slack;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.SLACK;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var app = appConfigService.findActiveByOrg(ctx.organizationId()).orElse(null);
        if (app != null) {
            var withButtons = ctx.eventType() == NotificationEventType.QUERY_SUBMITTED;
            botMessenger.postMessage(app.botToken(), targetChannel(config, app),
                    blockKitFactory.fallbackText(ctx), blockKitFactory.buildBlocks(ctx, withButtons));
            return;
        }
        post(config, blockKitFactory.buildEventPayload(ctx, config.channel()), "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeSlack(channel.getConfigJson());
        var app = appConfigService.findActiveByOrg(channel.getOrganizationId()).orElse(null);
        if (app != null) {
            botMessenger.postMessage(app.botToken(), targetChannel(config, app),
                    blockKitFactory.testText(), null);
            return;
        }
        post(config, blockKitFactory.buildTestPayload(config.channel()), "test");
    }

    private static String targetChannel(SlackChannelConfig config, DecryptedSlackApp app) {
        return (config.channel() != null && !config.channel().isBlank())
                ? config.channel()
                : app.defaultChannelId();
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
