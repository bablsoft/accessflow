package com.partqam.accessflow.notifications.internal.strategy;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationDeliveryException;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.NotificationContext;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.codec.WebhookChannelConfig;
import com.partqam.accessflow.notifications.internal.config.NotificationsProperties;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
class WebhookNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final WebhookPayloadFactory payloadFactory;
    private final RestClient restClient;
    private final TaskScheduler taskScheduler;
    private final NotificationsProperties properties;
    private final NotificationChannelRepository channelRepository;

    WebhookNotificationStrategy(ChannelConfigCodec codec,
                                WebhookPayloadFactory payloadFactory,
                                RestClient restClient,
                                @Qualifier("notificationsTaskScheduler") TaskScheduler taskScheduler,
                                NotificationsProperties properties,
                                NotificationChannelRepository channelRepository) {
        this.codec = codec;
        this.payloadFactory = payloadFactory;
        this.restClient = restClient;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.channelRepository = channelRepository;
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.WEBHOOK;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var body = payloadFactory.buildBody(ctx);
        attempt(channel.getId(), ctx.eventType(), body, 0);
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var body = payloadFactory.buildTestBody();
        attemptOnce(channel, NotificationEventType.TEST, body);
    }

    private void attempt(UUID channelId, NotificationEventType eventType, String body, int attempt) {
        var channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.isActive()) {
            log.debug("Skipping webhook retry for missing/inactive channel {}", channelId);
            return;
        }
        try {
            attemptOnce(channel, eventType, body);
        } catch (NotificationDeliveryException ex) {
            var nextDelay = nextRetryDelay(attempt);
            if (nextDelay == null) {
                // TODO(audit): record exhausted webhook delivery once the audit module exists.
                log.error("Webhook delivery exhausted retries for channel {} ({})",
                        channelId, eventType, ex);
                return;
            }
            log.warn("Webhook delivery attempt {} failed for channel {} ({}); retrying in {}",
                    attempt + 1, channelId, eventType, nextDelay);
            taskScheduler.schedule(
                    () -> attempt(channelId, eventType, body, attempt + 1),
                    Instant.now().plus(nextDelay));
        }
    }

    private void attemptOnce(NotificationChannelEntity channel, NotificationEventType eventType,
                             String body) {
        var config = codec.decodeWebhook(channel.getConfigJson());
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var signature = "sha256=" + HmacSigner.sha256Hex(bytes, config.secretPlain());
        var deliveryId = UUID.randomUUID();
        try {
            restClient.post()
                    .uri(config.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-AccessFlow-Event", eventType.name())
                    .header("X-AccessFlow-Signature", signature)
                    .header("X-AccessFlow-Delivery", deliveryId.toString())
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted webhook {} delivery {} to {}", eventType, deliveryId, config.url());
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "Webhook returned " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("Webhook delivery failed", ex);
        }
    }

    private Duration nextRetryDelay(int attempt) {
        var schedule = List.of(
                properties.retry().first(),
                properties.retry().second(),
                properties.retry().third());
        if (attempt >= schedule.size()) {
            return null;
        }
        return schedule.get(attempt);
    }
}
