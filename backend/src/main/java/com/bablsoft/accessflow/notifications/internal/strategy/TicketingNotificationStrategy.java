package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.events.NotificationDeliveryExhaustedEvent;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingTrigger;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Shared ticketing-system delivery flow (AF-453): trigger filtering, create-once dedupe, HTTP
 * create with the standard notification retry/backoff, and — on success — persisting the
 * {@code query_tickets} link and a {@code TICKET_CREATED} audit row. Subclasses supply the
 * system-specific config decoding, request building, and response parsing.
 *
 * <p>Like PagerDuty, a ticketing channel only reacts to the {@link TicketingTrigger}s the operator
 * selected; every other event resolved to the channel is dropped before any HTTP call.
 */
@Slf4j
abstract class TicketingNotificationStrategy implements NotificationChannelStrategy {

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    private final RestClient restClient;
    private final TaskScheduler taskScheduler;
    private final NotificationsProperties properties;
    private final NotificationChannelRepository channelRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final QueryTicketService queryTicketService;
    private final AuditLogService auditLogService;

    protected TicketingNotificationStrategy(RestClient restClient,
                                            TaskScheduler taskScheduler,
                                            NotificationsProperties properties,
                                            NotificationChannelRepository channelRepository,
                                            ApplicationEventPublisher eventPublisher,
                                            QueryTicketService queryTicketService,
                                            AuditLogService auditLogService) {
        this.restClient = restClient;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.channelRepository = channelRepository;
        this.eventPublisher = eventPublisher;
        this.queryTicketService = queryTicketService;
        this.auditLogService = auditLogService;
    }

    /** HTTP request to create a ticket. */
    protected record TicketRequest(URI uri, String authorizationHeader, String body) {
    }

    /** Parsed identity of a freshly created ticket. */
    protected record TicketRef(String externalId, String externalKey, String url,
                               String initialStatus) {
    }

    /** Read-only connectivity probe for {@code POST .../test}. */
    protected record TestRequest(URI uri, String authorizationHeader) {
    }

    protected abstract TicketingChannelConfig decodeConfig(String storedJson);

    protected abstract TicketRequest buildCreateRequest(NotificationContext ctx,
                                                        TicketingChannelConfig config);

    protected abstract TicketRef parseTicketRef(String responseBody, TicketingChannelConfig config);

    protected abstract TestRequest buildTestRequest(TicketingChannelConfig config);

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        if (ctx.queryRequestId() == null) {
            log.debug("Skipping {} channel {} for non-query event {}", supports(), channel.getId(),
                    ctx.eventType());
            return;
        }
        var config = decodeConfig(channel.getConfigJson());
        var trigger = TicketingTrigger.forEvent(ctx.eventType());
        if (trigger.isEmpty() || !config.triggers().contains(trigger.get())) {
            log.debug("Skipping {} channel {} for non-matching event {}", supports(),
                    channel.getId(), ctx.eventType());
            return;
        }
        if (queryTicketService.existsFor(channel.getId(), ctx.queryRequestId(),
                ctx.eventType().name())) {
            log.debug("Skipping {} channel {}: ticket already exists for query {} event {}",
                    supports(), channel.getId(), ctx.queryRequestId(), ctx.eventType());
            return;
        }
        var request = buildCreateRequest(ctx, config);
        attempt(channel.getId(), ctx, request, 0);
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = decodeConfig(channel.getConfigJson());
        var request = buildTestRequest(config);
        try {
            restClient.get()
                    .uri(request.uri())
                    .header(HttpHeaders.AUTHORIZATION, request.authorizationHeader())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    supports() + " returned " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException(supports() + " connectivity test failed", ex);
        }
    }

    private void attempt(UUID channelId, NotificationContext ctx, TicketRequest request,
                         int attempt) {
        var channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.isActive()) {
            log.debug("Skipping {} retry for missing/inactive channel {}", supports(), channelId);
            return;
        }
        String responseBody;
        try {
            responseBody = attemptOnce(ctx.eventType(), request);
        } catch (NotificationDeliveryException ex) {
            var nextDelay = nextRetryDelay(attempt);
            if (nextDelay == null) {
                log.error("{} ticket creation exhausted retries for channel {} ({})", supports(),
                        channelId, ctx.eventType(), ex);
                publishExhaustedEvent(channel, ctx.eventType(), attempt + 1, ex);
                return;
            }
            log.warn("{} ticket creation attempt {} failed for channel {} ({}); retrying in {}",
                    supports(), attempt + 1, channelId, ctx.eventType(), nextDelay);
            taskScheduler.schedule(
                    () -> attempt(channelId, ctx, request, attempt + 1),
                    Instant.now().plus(nextDelay));
            return;
        }
        recordTicket(channel, ctx, responseBody);
    }

    private void recordTicket(NotificationChannelEntity channel, NotificationContext ctx,
                              String responseBody) {
        TicketRef ref;
        try {
            ref = parseTicketRef(responseBody, decodeConfig(channel.getConfigJson()));
        } catch (RuntimeException ex) {
            log.error("{} ticket created for query {} but the response could not be parsed",
                    supports(), ctx.queryRequestId(), ex);
            return;
        }
        try {
            queryTicketService.recordCreated(new RecordTicketCommand(
                    ctx.organizationId(),
                    ctx.queryRequestId(),
                    channel.getId(),
                    supports().name(),
                    ctx.eventType().name(),
                    ref.externalId(),
                    ref.externalKey(),
                    ref.url(),
                    ref.initialStatus()));
        } catch (DataIntegrityViolationException ex) {
            // A concurrent delivery for the same (channel, query, event) won the race — the
            // remote ticket is a duplicate, but the link table stays consistent.
            log.warn("{} ticket {} for query {} raced an existing link; keeping the first",
                    supports(), ref.externalKey(), ctx.queryRequestId());
            return;
        }
        writeCreatedAudit(channel, ctx, ref);
        log.info("Created {} ticket {} for query {} ({})", supports(), ref.externalKey(),
                ctx.queryRequestId(), ctx.eventType());
    }

    private void writeCreatedAudit(NotificationChannelEntity channel, NotificationContext ctx,
                                   TicketRef ref) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("ticket_system", supports().name());
            metadata.put("channel_id", channel.getId().toString());
            metadata.put("external_key", ref.externalKey());
            metadata.put("trigger_event", ctx.eventType().name());
            metadata.put("query_request_id", ctx.queryRequestId().toString());
            if (ref.url() != null) {
                metadata.put("url", ref.url());
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.TICKET_CREATED,
                    AuditResourceType.QUERY_TICKET,
                    ctx.queryRequestId(),
                    ctx.organizationId(),
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Failed to write TICKET_CREATED audit row for query {}",
                    ctx.queryRequestId(), ex);
        }
    }

    private String attemptOnce(NotificationEventType eventType, TicketRequest request) {
        var bytes = request.body().getBytes(StandardCharsets.UTF_8);
        try {
            var body = restClient.post()
                    .uri(request.uri())
                    .header(HttpHeaders.AUTHORIZATION, request.authorizationHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(bytes)
                    .retrieve()
                    .body(String.class);
            log.debug("Posted {} {} ticket to {}", supports(), eventType, request.uri());
            return body;
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    supports() + " returned " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException(supports() + " delivery failed", ex);
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
                    supports().name(),
                    eventType.name(),
                    attemptCount,
                    extractHttpStatus(ex),
                    truncate(ex.getMessage())));
        } catch (RuntimeException publishEx) {
            log.error("Failed to publish NotificationDeliveryExhaustedEvent for channel {} ({})",
                    channel.getId(), eventType, publishEx);
        }
    }

    protected static String basicAuth(String user, String secret) {
        var token = Base64.getEncoder()
                .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
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
