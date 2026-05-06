package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationEventType;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Carries everything the channel strategies need to render a notification. Built once per event
 * and passed by reference to each strategy.
 */
public record NotificationContext(
        NotificationEventType eventType,
        UUID organizationId,
        UUID queryRequestId,
        QueryType queryType,
        String fullSqlText,
        String sqlPreview200,
        String sqlPreview300,
        RiskLevel riskLevel,
        Integer riskScore,
        String aiSummary,
        UUID datasourceId,
        String datasourceName,
        UUID submittedByUserId,
        String submitterEmail,
        String submitterDisplayName,
        String justification,
        UUID reviewerUserId,
        String reviewerDisplayName,
        String reviewerComment,
        URI reviewUrl,
        List<RecipientView> recipients,
        Instant occurredAt) {
}
