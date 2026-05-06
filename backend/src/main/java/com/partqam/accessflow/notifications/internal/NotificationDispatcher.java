package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private final Map<NotificationChannelType, NotificationChannelStrategy> strategies;

    NotificationDispatcher(NotificationContextBuilder contextBuilder,
                           NotificationChannelRepository channelRepository,
                           List<NotificationChannelStrategy> strategyBeans) {
        this.contextBuilder = contextBuilder;
        this.channelRepository = channelRepository;
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
