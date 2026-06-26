package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Carries everything the channel strategies need to render a notification. Built once per event
 * and passed by reference to each strategy. {@code locale} is the BCP-47 code resolved from the
 * organization's default language and drives both subject-line resolution and Thymeleaf
 * {@code #{...}} lookups. {@code approvalTimeoutHours} is only populated for
 * {@link NotificationEventType#REVIEW_TIMEOUT} events.
 *
 * <p>The {@code anomaly*} fields are only populated for
 * {@link NotificationEventType#ANOMALY_DETECTED} (UBA, AF-383) — every query-backed field
 * ({@code queryRequestId}, {@code queryType}, SQL previews, {@code riskLevel}) is null in that case,
 * and the anomaly explanation is carried in {@code aiSummary}. The backward-compatible constructor
 * (without the anomaly fields) defaults them to null for the query/access notification paths.
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
        Instant occurredAt,
        String locale,
        Integer approvalTimeoutHours,
        UUID anomalyId,
        String anomalyFeature,
        Double anomalyScore,
        Double anomalyObservedValue,
        Double anomalyBaselineMean,
        String anomalyUserLabel,
        WeeklyDigestData digest,
        UUID attestationCampaignId,
        String attestationCampaignName,
        Instant attestationDueAt) {

    /** Backward-compatible constructor for the query / access notification paths (no anomaly fields). */
    public NotificationContext(
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
            Instant occurredAt,
            String locale,
            Integer approvalTimeoutHours) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** Compatibility constructor for the anomaly / weekly-digest paths (no attestation fields). */
    public NotificationContext(
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
            Instant occurredAt,
            String locale,
            Integer approvalTimeoutHours,
            UUID anomalyId,
            String anomalyFeature,
            Double anomalyScore,
            Double anomalyObservedValue,
            Double anomalyBaselineMean,
            String anomalyUserLabel,
            WeeklyDigestData digest) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, anomalyId, anomalyFeature, anomalyScore,
                anomalyObservedValue, anomalyBaselineMean, anomalyUserLabel, digest,
                null, null, null);
    }
}
