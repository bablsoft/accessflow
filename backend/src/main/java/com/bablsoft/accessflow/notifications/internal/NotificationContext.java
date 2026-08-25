package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
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
 *
 * <p>{@code apiRequestId} is only populated for the {@code API_REQUEST_*} events (AF-500) and is
 * mutually exclusive with {@code queryRequestId} — an in-app notification references at most one of
 * a query request or an API request.
 *
 * <p>The {@code grant*} fields are only populated for {@link NotificationEventType#GRANT_STALE}
 * (#625). That event reuses {@code datasourceId}/{@code datasourceName} for the granted resource
 * (a datasource <em>or</em> an API connector — {@code grantResourceKind} says which) and
 * {@code submittedByUserId}/{@code submitterEmail} for the grant holder.
 * {@code grantDaysSinceLastUse} is null when the grant has never been used, which the templates
 * render differently from a large number of days.
 *
 * <p>The {@code export*} fields are only populated for
 * {@link NotificationEventType#SENSITIVE_RESULT_EXPORTED} (#626). That event reuses
 * {@code submittedByUserId}/{@code submitterEmail} for the <em>exporter</em> (who may not be the
 * query's submitter) and {@code executionRowsAffected} for the exported row count;
 * {@code exportClassifications} is the preformatted classification list (e.g. {@code "PCI, PHI"})
 * and {@code exportTrigger} is {@code "endpoint"} or {@code "email_attachment"}.
 *
 * <p>The {@code deployment*} fields are only populated for the {@code DEPLOYMENT_*} events (#695).
 * Those events reuse {@code datasourceId}/{@code datasourceName} for the pipeline,
 * {@code submittedByUserId}/{@code submitterEmail} for the submitter, and {@code justification}
 * for the submission (or break-glass) justification. {@code deploymentOutcome} is only set for
 * {@code DEPLOYMENT_OUTCOME_FAILED}; {@code deploymentDecisionReason} carries the decision
 * provenance ({@code "routing:&lt;policyId&gt;"}, {@code "freeze:&lt;windowId&gt;"},
 * {@code "review_timeout"}, or null for a reviewer verdict).
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
        Instant attestationDueAt,
        UUID apiRequestId,
        QueryStatus executionStatus,
        Long executionRowsAffected,
        Long executionDurationMs,
        GrantResourceKind grantResourceKind,
        Long grantDaysSinceLastUse,
        GrantUsageRecommendation grantRecommendation,
        String exportFormat,
        String exportClassifications,
        String exportTrigger,
        UUID deploymentRequestId,
        String environmentName,
        String deploymentVersion,
        DeploymentOutcome deploymentOutcome,
        String deploymentDecisionReason) {

    /** Compatibility constructor without the #695 deployment fields — every non-deployment path. */
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
            WeeklyDigestData digest,
            UUID attestationCampaignId,
            String attestationCampaignName,
            Instant attestationDueAt,
            UUID apiRequestId,
            QueryStatus executionStatus,
            Long executionRowsAffected,
            Long executionDurationMs,
            GrantResourceKind grantResourceKind,
            Long grantDaysSinceLastUse,
            GrantUsageRecommendation grantRecommendation,
            String exportFormat,
            String exportClassifications,
            String exportTrigger) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, anomalyId, anomalyFeature, anomalyScore,
                anomalyObservedValue, anomalyBaselineMean, anomalyUserLabel, digest,
                attestationCampaignId, attestationCampaignName, attestationDueAt, apiRequestId,
                executionStatus, executionRowsAffected, executionDurationMs, grantResourceKind,
                grantDaysSinceLastUse, grantRecommendation, exportFormat, exportClassifications,
                exportTrigger, null, null, null, null, null);
    }

    /**
     * Compatibility constructor without the #626 result-export fields — every path other than
     * {@code SENSITIVE_RESULT_EXPORTED}.
     */
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
            WeeklyDigestData digest,
            UUID attestationCampaignId,
            String attestationCampaignName,
            Instant attestationDueAt,
            UUID apiRequestId,
            QueryStatus executionStatus,
            Long executionRowsAffected,
            Long executionDurationMs,
            GrantResourceKind grantResourceKind,
            Long grantDaysSinceLastUse,
            GrantUsageRecommendation grantRecommendation) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, anomalyId, anomalyFeature, anomalyScore,
                anomalyObservedValue, anomalyBaselineMean, anomalyUserLabel, digest,
                attestationCampaignId, attestationCampaignName, attestationDueAt, apiRequestId,
                executionStatus, executionRowsAffected, executionDurationMs, grantResourceKind,
                grantDaysSinceLastUse, grantRecommendation, null, null, null);
    }

    /**
     * Compatibility constructor without the #625 grant-staleness fields — every path other than
     * {@code GRANT_STALE}.
     */
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
            WeeklyDigestData digest,
            UUID attestationCampaignId,
            String attestationCampaignName,
            Instant attestationDueAt,
            UUID apiRequestId,
            QueryStatus executionStatus,
            Long executionRowsAffected,
            Long executionDurationMs) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, anomalyId, anomalyFeature, anomalyScore,
                anomalyObservedValue, anomalyBaselineMean, anomalyUserLabel, digest,
                attestationCampaignId, attestationCampaignName, attestationDueAt, apiRequestId,
                executionStatus, executionRowsAffected, executionDurationMs, null, null, null);
    }

    /** Compatibility constructor without the #627 execution-outcome fields. */
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
            WeeklyDigestData digest,
            UUID attestationCampaignId,
            String attestationCampaignName,
            Instant attestationDueAt,
            UUID apiRequestId) {
        this(eventType, organizationId, queryRequestId, queryType, fullSqlText, sqlPreview200,
                sqlPreview300, riskLevel, riskScore, aiSummary, datasourceId, datasourceName,
                submittedByUserId, submitterEmail, submitterDisplayName, justification,
                reviewerUserId, reviewerDisplayName, reviewerComment, reviewUrl, recipients,
                occurredAt, locale, approvalTimeoutHours, anomalyId, anomalyFeature, anomalyScore,
                anomalyObservedValue, anomalyBaselineMean, anomalyUserLabel, digest,
                attestationCampaignId, attestationCampaignName, attestationDueAt, apiRequestId,
                null, null, null);
    }

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
                null, null, null, null);
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
                null, null, null, null);
    }
}
