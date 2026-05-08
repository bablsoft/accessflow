package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
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
    private final Map<NotificationChannelType, NotificationChannelStrategy> strategies;

    NotificationDispatcher(NotificationContextBuilder contextBuilder,
                           NotificationChannelRepository channelRepository,
                           UserNotificationService userNotificationService,
                           ObjectMapper objectMapper,
                           List<NotificationChannelStrategy> strategyBeans) {
        this.contextBuilder = contextBuilder;
        this.channelRepository = channelRepository;
        this.userNotificationService = userNotificationService;
        this.objectMapper = objectMapper;
        var map = new EnumMap<NotificationChannelType, NotificationChannelStrategy>(
                NotificationChannelType.class);
        for (NotificationChannelStrategy strategy : strategyBeans) {
            map.put(strategy.supports(), strategy);
        }
        this.strategies = map;
    }

    void dispatch(NotificationEventType eventType, UUID queryRequestId,
                  UUID reviewerUserId, String reviewerComment) {
        var contextOpt = contextBuilder.build(eventType, queryRequestId, reviewerUserId,
                reviewerComment);
        if (contextOpt.isEmpty()) {
            log.debug("Skipping {} for unknown query {}", eventType, queryRequestId);
            return;
        }
        var ctx = contextOpt.get();
        recordInAppNotifications(ctx);
        var channels = resolveChannels(eventType, ctx);
        if (channels.isEmpty()) {
            log.debug("No active channels for event {} on query {}", eventType, queryRequestId);
            return;
        }
        for (NotificationChannelEntity channel : channels) {
            var strategy = strategies.get(channel.getChannelType());
            if (strategy == null) {
                log.warn("No strategy registered for channel type {}", channel.getChannelType());
                continue;
            }
            try {
                strategy.deliver(ctx, channel);
            } catch (RuntimeException ex) {
                log.error("Failed to deliver {} via channel {} ({})",
                        eventType, channel.getId(), channel.getChannelType(), ex);
            }
        }
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
        if (eventType == NotificationEventType.AI_HIGH_RISK) {
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
