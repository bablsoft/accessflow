package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.bablsoft.accessflow.notifications.internal.strategy.EmailNotificationStrategy;
import com.bablsoft.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator that routes a notification event to the active channels configured for the
 * triggering review plan (or organization, for {@link NotificationEventType#AI_HIGH_RISK}).
 *
 * <p>All per-channel exceptions are caught individually so a misconfigured channel cannot
 * affect any other delivery, and listeners can swallow the dispatcher's own exceptions to
 * keep the workflow state machine isolated from notification failures.
 */
@Component
@Slf4j
class NotificationDispatcher {

    private final NotificationContextBuilder contextBuilder;
    private final NotificationChannelRepository channelRepository;
    private final UserNotificationService userNotificationService;
    private final ObjectMapper objectMapper;
    private final SystemEmailFallback systemEmailFallback;
    private final Map<NotificationChannelType, NotificationChannelStrategy> strategies;

    NotificationDispatcher(NotificationContextBuilder contextBuilder,
                           NotificationChannelRepository channelRepository,
                           UserNotificationService userNotificationService,
                           ObjectMapper objectMapper,
                           SystemEmailFallback systemEmailFallback,
                           List<NotificationChannelStrategy> strategyBeans) {
        this.contextBuilder = contextBuilder;
        this.channelRepository = channelRepository;
        this.userNotificationService = userNotificationService;
        this.objectMapper = objectMapper;
        this.systemEmailFallback = systemEmailFallback;
        var map = new EnumMap<NotificationChannelType, NotificationChannelStrategy>(
                NotificationChannelType.class);
        for (NotificationChannelStrategy strategy : strategyBeans) {
            map.put(strategy.supports(), strategy);
        }
        this.strategies = map;
    }

    void dispatch(NotificationEventType eventType, UUID queryRequestId,
                  UUID reviewerUserId, String reviewerComment,
                  Integer approvalTimeoutHours) {
        var contextOpt = contextBuilder.build(eventType, queryRequestId, reviewerUserId,
                reviewerComment, approvalTimeoutHours);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping {} for unknown query {}", eventType, queryRequestId);
            return;
        }
        deliver(eventType, contextOpt.get());
    }

    /** Dispatch a behavioural anomaly (UBA, AF-383) — non-query-backed; fans out to all active
     *  org channels, mirroring {@code AI_HIGH_RISK}. */
    void dispatchAnomaly(UUID anomalyId, UUID organizationId) {
        var contextOpt = contextBuilder.buildAnomaly(anomalyId, organizationId);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping ANOMALY_DETECTED for unknown anomaly {}", anomalyId);
            return;
        }
        deliver(NotificationEventType.ANOMALY_DETECTED, contextOpt.get());
    }

    /** Dispatch the opt-in weekly digest (AF-498) — non-query-backed; fans out to the user's email
     *  plus all active org chat channels, mirroring {@code ANOMALY_DETECTED}. */
    void dispatchWeeklyDigest(com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent event) {
        var contextOpt = contextBuilder.buildWeeklyDigest(event);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping WEEKLY_DIGEST for unknown/inactive user {}", event.userId());
            return;
        }
        deliver(NotificationEventType.WEEKLY_DIGEST, contextOpt.get());
    }

    /** Dispatch a freshly-opened attestation campaign (AF-384) — non-query-backed; fans out to all
     *  active org channels, mirroring {@code ANOMALY_DETECTED}. */
    void dispatchAttestationCampaignOpened(UUID campaignId, UUID organizationId) {
        var contextOpt = contextBuilder.buildAttestationCampaign(campaignId, organizationId);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping ATTESTATION_CAMPAIGN_OPENED for unknown campaign {}", campaignId);
            return;
        }
        deliver(NotificationEventType.ATTESTATION_CAMPAIGN_OPENED, contextOpt.get());
    }

    /** Dispatch an approved-erasure notification to the submitter (AF-499). */
    void dispatchErasureApproved(UUID organizationId, UUID requestedBy) {
        var contextOpt = contextBuilder.buildLifecycleErasure(organizationId, requestedBy);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping ERASURE_APPROVED for unknown/inactive submitter {}", requestedBy);
            return;
        }
        deliver(NotificationEventType.ERASURE_APPROVED, contextOpt.get());
    }

    /** Dispatch an API-request notification (AF-500) — non-query-backed; recipients resolved by the
     *  context builder (reviewers/admins for SUBMITTED, submitter for terminal events). */
    void dispatchApiRequest(NotificationEventType eventType, UUID apiRequestId) {
        var contextOpt = contextBuilder.buildApiRequest(eventType, apiRequestId);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping {} for unknown API request {}", eventType, apiRequestId);
            return;
        }
        deliver(eventType, contextOpt.get());
    }

    /** Dispatch a connector-scoped OAuth2 token-failure alert (AF-500 / #506) — fans out to all
     *  active org channels (the connector is effectively down); recipients are active org admins. */
    void dispatchApiConnector(NotificationEventType eventType, UUID connectorId) {
        var contextOpt = contextBuilder.buildApiConnector(eventType, connectorId);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping {} for unknown connector {}", eventType, connectorId);
            return;
        }
        deliver(eventType, contextOpt.get());
    }

    private void deliver(NotificationEventType eventType, NotificationContext ctx) {
        recordInAppNotifications(ctx);
        var channels = resolveChannels(eventType, ctx);
        boolean emailChannelDelivered = false;
        for (NotificationChannelEntity channel : channels) {
            var strategy = strategies.get(channel.getChannelType());
            if (strategy == null) {
                log.warn("No strategy registered for channel type {}", channel.getChannelType());
                continue;
            }
            try {
                strategy.deliver(ctx, channel);
                if (channel.getChannelType() == NotificationChannelType.EMAIL) {
                    emailChannelDelivered = true;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to deliver {} via channel {} ({})",
                        eventType, channel.getId(), channel.getChannelType(), ex);
            }
        }
        maybeDeliverViaSystemSmtp(ctx, channels, emailChannelDelivered);
    }

    private void maybeDeliverViaSystemSmtp(NotificationContext ctx,
                                           List<NotificationChannelEntity> resolvedChannels,
                                           boolean emailChannelDelivered) {
        if (emailChannelDelivered) {
            return;
        }
        boolean hasAnyEmailChannel = resolvedChannels.stream()
                .anyMatch(c -> c.getChannelType() == NotificationChannelType.EMAIL);
        if (hasAnyEmailChannel) {
            return;
        }
        if (ctx.recipients() == null || ctx.recipients().isEmpty()) {
            return;
        }
        if (!EmailNotificationStrategy.hasTemplateFor(ctx.eventType())) {
            return;
        }
        systemEmailFallback.deliverIfPossible(ctx);
    }

    private void recordInAppNotifications(NotificationContext ctx) {
        if (ctx.eventType() == NotificationEventType.TEST) {
            return;
        }
        if (ctx.recipients() == null || ctx.recipients().isEmpty()) {
            return;
        }
        Set<UUID> recipientIds = new LinkedHashSet<>();
        for (RecipientView r : ctx.recipients()) {
            if (r.userId() != null) {
                recipientIds.add(r.userId());
            }
        }
        if (recipientIds.isEmpty()) {
            return;
        }
        try {
            userNotificationService.recordForUsers(
                    ctx.eventType(),
                    recipientIds,
                    ctx.organizationId(),
                    ctx.queryRequestId(),
                    ctx.apiRequestId(),
                    buildPayload(ctx));
        } catch (RuntimeException ex) {
            log.error("Failed to persist in-app notifications for event {} on query {}",
                    ctx.eventType(), ctx.queryRequestId(), ex);
        }
    }

    private String buildPayload(NotificationContext ctx) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (ctx.queryRequestId() != null) {
            payload.put("query_id", ctx.queryRequestId().toString());
        }
        if (ctx.apiRequestId() != null) {
            payload.put("api_id", ctx.apiRequestId().toString());
        }
        if (ctx.datasourceName() != null) {
            payload.put("datasource", ctx.datasourceName());
        }
        if (ctx.submitterEmail() != null) {
            payload.put("submitter", ctx.submitterEmail());
        }
        if (ctx.submitterDisplayName() != null) {
            payload.put("submitter_name", ctx.submitterDisplayName());
        }
        if (ctx.riskLevel() != null) {
            payload.put("risk_level", ctx.riskLevel().name());
        }
        if (ctx.reviewerDisplayName() != null) {
            payload.put("reviewer", ctx.reviewerDisplayName());
        }
        if (ctx.reviewerComment() != null) {
            payload.put("reviewer_comment", ctx.reviewerComment());
        }
        return payload.toString();
    }

    private List<NotificationChannelEntity> resolveChannels(NotificationEventType eventType,
                                                            NotificationContext ctx) {
        if (eventType == NotificationEventType.AI_HIGH_RISK
                || eventType == NotificationEventType.ANOMALY_DETECTED
                || eventType == NotificationEventType.BREAK_GLASS_EXECUTED
                || eventType == NotificationEventType.WEEKLY_DIGEST
                || eventType == NotificationEventType.ATTESTATION_CAMPAIGN_OPENED
                || eventType == NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED) {
            return channelRepository.findAllByOrganizationIdAndActiveTrue(ctx.organizationId());
        }
        var planChannels = lookupPlanChannelIds(ctx);
        if (planChannels.isEmpty()) {
            return List.of();
        }
        return channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(
                ctx.organizationId(), planChannels);
    }

    private List<UUID> lookupPlanChannelIds(NotificationContext ctx) {
        return contextBuilder.lookupPlanChannelIds(ctx.datasourceId());
    }
}
