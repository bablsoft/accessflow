package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional per-subscription unit of work for the weekly digest (AF-498). Builds the user's
 * summary, publishes {@link WeeklyDigestReadyEvent}, and stamps {@code last_sent_at} in one
 * transaction so the {@code notifications} {@code @ApplicationModuleListener} (AFTER_COMMIT) actually
 * fires — an event published outside a transaction would be silently dropped.
 */
@Service
@RequiredArgsConstructor
public class WeeklyDigestDispatchService {

    private final DashboardWeeklySummaryBuilder summaryBuilder;
    private final DashboardDigestSubscriptionRepository subscriptionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void publishDigest(UUID subscriptionId, Instant now) {
        var subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null) {
            return;
        }
        DashboardWeeklySummary summary = summaryBuilder.build(
                subscription.getOrganizationId(), subscription.getUserId(), null);
        eventPublisher.publishEvent(new WeeklyDigestReadyEvent(
                summary.organizationId(),
                summary.userId(),
                summary.weekStart(),
                summary.weekEnd(),
                summary.totalQueries(),
                summary.pendingApprovals(),
                summary.openAnomalies(),
                summary.openSuggestions()));
        subscription.setLastSentAt(now);
        subscription.setUpdatedAt(now);
        subscriptionRepository.save(subscription);
    }
}
