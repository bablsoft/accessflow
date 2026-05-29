package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.PagerDutyTrigger;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers notifications to PagerDuty via the Events API v2 ({@code event_action=trigger}).
 *
 * <p>Unlike the chat dispatchers, a PagerDuty channel only pages for the {@link PagerDutyTrigger}s
 * the operator selected. The dispatcher calls {@link #deliver} for every resolved channel
 * regardless of event type, so the trigger filter lives here: events with no matching trigger are
 * dropped before any HTTP call. Retry/backoff mirrors {@link WebhookNotificationStrategy}.
 */
@Component
@Slf4j
class PagerDutyNotificationStrategy implements NotificationChannelStrategy {

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    private final ChannelConfigCodec codec;
    private final PagerDutyPayloadFactory payloadFactory;
    private final RestClient restClient;
    private final TaskScheduler taskScheduler;
    private final NotificationsProperties properties;
    private final NotificationChannelRepository channelRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final URI enqueueUri;

    PagerDutyNotificationStrategy(ChannelConfigCodec codec,
                                  PagerDutyPayloadFactory payloadFactory,
                                  RestClient restClient,
                                  @Qualifier("notificationsTaskScheduler") TaskScheduler taskScheduler,
                                  NotificationsProperties properties,
                                  NotificationChannelRepository channelRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.codec = codec;
        this.payloadFactory = payloadFactory;
        this.restClient = restClient;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.channelRepository = channelRepository;
        this.eventPublisher = eventPublisher;
        var base = properties.pagerDutyApiBaseUrl().toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        this.enqueueUri = URI.create(base + "v2/enqueue");
    }

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.PAGERDUTY;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodePagerDuty(channel.getConfigJson());
        var trigger = PagerDutyTrigger.forEvent(ctx.eventType());
        if (trigger.isEmpty() || !config.triggers().contains(trigger.get())) {
            log.debug("Skipping PagerDuty channel {} for non-matching event {}",
                    channel.getId(), ctx.eventType());
            return;
        }
        var body = payloadFactory.buildEventBody(ctx, config);
        attempt(channel.getId(), ctx.eventType(), body, 0);
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodePagerDuty(channel.getConfigJson());
        var body = payloadFactory.buildTestBody(config);
        attemptOnce(NotificationEventType.TEST, body);
    }

    private void attempt(UUID channelId, NotificationEventType eventType, String body, int attempt) {
        var channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.isActive()) {
            log.debug("Skipping PagerDuty retry for missing/inactive channel {}", channelId);
            return;
        }
        try {
            attemptOnce(eventType, body);
        } catch (NotificationDeliveryException ex) {
            var nextDelay = nextRetryDelay(attempt);
            if (nextDelay == null) {
                log.error("PagerDuty delivery exhausted retries for channel {} ({})",
                        channelId, eventType, ex);
                publishExhaustedEvent(channel, eventType, attempt + 1, ex);
                return;
            }
            log.warn("PagerDuty delivery attempt {} failed for channel {} ({}); retrying in {}",
                    attempt + 1, channelId, eventType, nextDelay);
            taskScheduler.schedule(
                    () -> attempt(channelId, eventType, body, attempt + 1),
                    Instant.now().plus(nextDelay));
        }
    }

    private void publishExhaustedEvent(NotificationChannelEntity channel,
                                       NotificationEventType eventType,
                                       int attemptCount,
                                       NotificationDeliveryException ex) {
        try {
            eventPublisher.publishEvent(new NotificationDeliveryExhaustedEvent(
                    channel.getOrganizationId(),
                    channel.getId(),
                    NotificationChannelType.PAGERDUTY.name(),
                    eventType.name(),
                    attemptCount,
                    extractHttpStatus(ex),
                    truncate(ex.getMessage())));
        } catch (RuntimeException publishEx) {
            log.error("Failed to publish NotificationDeliveryExhaustedEvent for channel {} ({})",
                    channel.getId(), eventType, publishEx);
        }
    }

    private static Integer extractHttpStatus(NotificationDeliveryException ex) {
        if (ex.getCause() instanceof RestClientResponseException rcre) {
            return rcre.getStatusCode().value();
        }
        return null;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > LAST_ERROR_MAX_LENGTH
                ? message.substring(0, LAST_ERROR_MAX_LENGTH)
                : message;
    }

    private void attemptOnce(NotificationEventType eventType, String body) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            restClient.post()
                    .uri(enqueueUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted PagerDuty {} event to {}", eventType, enqueueUri);
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "PagerDuty returned " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("PagerDuty delivery failed", ex);
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
