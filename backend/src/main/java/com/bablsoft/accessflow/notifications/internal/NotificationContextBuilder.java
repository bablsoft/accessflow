package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationLookupService;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotificationLookupService;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotificationView;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a {@link NotificationContext} from a query request id by composing the public-facing
 * facades exposed by the {@code core} module. Never reaches into {@code core/internal}.
 */
@Component
@RequiredArgsConstructor
class NotificationContextBuilder {

    private final QueryRequestLookupService queryRequestLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final UserQueryService userQueryService;
    private final LocalizationConfigService localizationConfigService;
    private final BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    private final AttestationCampaignLookupService attestationCampaignLookupService;
    private final ApiRequestNotificationLookupService apiRequestNotificationLookupService;
    private final ApiConnectorNotificationLookupService apiConnectorNotificationLookupService;
    private final NotificationsProperties properties;

    List<UUID> lookupPlanChannelIds(UUID datasourceId) {
        return reviewPlanLookupService.findForDatasource(datasourceId)
                .map(ReviewPlanSnapshot::notifyChannelIds)
                .orElse(List.of());
    }

    Optional<NotificationContext> build(NotificationEventType eventType, UUID queryRequestId,
                                        UUID reviewerUserId, String reviewerComment,
                                        Integer approvalTimeoutHours) {
        var snapshot = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        var datasource = datasourceAdminService.getForAdmin(
                snapshot.datasourceId(), snapshot.organizationId());
        var submitter = userQueryService.findById(snapshot.submittedByUserId()).orElse(null);
        var reviewer = reviewerUserId != null
                ? userQueryService.findById(reviewerUserId).orElse(null)
                : null;
        var ai = aiAnalysisLookupService.findByQueryRequestId(queryRequestId).orElse(null);
        var plan = reviewPlanLookupService.findForDatasource(snapshot.datasourceId()).orElse(null);
        var recipients = resolveRecipients(eventType, snapshot, plan, submitter);
        var locale = localizationConfigService.getOrDefault(snapshot.organizationId())
                .defaultLanguage();
        return Optional.of(new NotificationContext(
                eventType,
                snapshot.organizationId(),
                snapshot.id(),
                snapshot.queryType(),
                snapshot.sqlText(),
                truncate(snapshot.sqlText(), 200),
                truncate(snapshot.sqlText(), 300),
                ai != null ? ai.riskLevel() : null,
                ai != null ? ai.riskScore() : null,
                ai != null ? ai.summary() : null,
                snapshot.datasourceId(),
                datasource != null ? datasource.name() : null,
                snapshot.submittedByUserId(),
                submitter != null ? submitter.email() : null,
                submitter != null ? submitter.displayName() : null,
                null,
                reviewer != null ? reviewer.id() : null,
                reviewer != null ? reviewer.displayName() : null,
                reviewerComment,
                buildReviewUrl(snapshot.id()),
                recipients,
                Instant.now(),
                locale,
                approvalTimeoutHours));
    }

    private List<RecipientView> resolveRecipients(NotificationEventType eventType,
                                                  QueryRequestSnapshot snapshot,
                                                  ReviewPlanSnapshot plan,
                                                  UserView submitter) {
        return switch (eventType) {
            // QUERY_ESCALATED targets the same reviewer set as QUERY_SUBMITTED — a routing policy
            // raised the approval bar, but the people who must act are still the plan's reviewers.
            case QUERY_SUBMITTED, QUERY_ESCALATED -> reviewersForLowestStage(plan, snapshot);
            case QUERY_APPROVED, QUERY_REJECTED -> submitter != null
                    ? List.of(toRecipient(submitter))
                    : List.of();
            case REVIEW_TIMEOUT -> reviewTimeoutRecipients(snapshot, submitter);
            // AI_HIGH_RISK and BREAK_GLASS_EXECUTED both fan out to every active org admin (AF-385).
            case AI_HIGH_RISK, BREAK_GLASS_EXECUTED -> userQueryService
                    .findByOrganizationAndRole(snapshot.organizationId(), UserRoleType.ADMIN)
                    .stream()
                    .filter(UserView::active)
                    .map(NotificationContextBuilder::toRecipient)
                    .toList();
            case TEST -> submitter != null ? List.of(toRecipient(submitter)) : List.of();
            // Access (JIT) events are not query-backed; they are handled by AccessNotificationListener.
            // Anomaly events are built via buildAnomaly(...); weekly digests via buildWeeklyDigest(...).
            // Access (JIT), anomaly, digest, attestation, and API-request events are not query-backed
            // and are built/dispatched by their own listeners (AF-500: ApiNotificationListener).
            case ACCESS_REQUEST_SUBMITTED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_REJECTED,
                 ACCESS_GRANT_EXPIRED, ACCESS_GRANT_REVOKED, ANOMALY_DETECTED, WEEKLY_DIGEST,
                 ATTESTATION_CAMPAIGN_OPENED, API_REQUEST_SUBMITTED, API_REQUEST_APPROVED,
                 API_REQUEST_EXECUTED, API_REQUEST_FAILED,
                 API_CONNECTOR_OAUTH2_TOKEN_FAILED, ERASURE_APPROVED -> List.of();
        };
    }

    /**
     * Builds the context for an approved data-erasure request (AF-499). Not query-backed: every
     * query field is null; the single recipient is the submitter so they learn their request was
     * approved. Returns empty when the submitter is unknown/inactive.
     */
    Optional<NotificationContext> buildLifecycleErasure(UUID organizationId, UUID requestedBy) {
        var submitter = userQueryService.findById(requestedBy)
                .filter(UserView::active)
                .orElse(null);
        if (submitter == null) {
            return Optional.empty();
        }
        var locale = localizationConfigService.getOrDefault(organizationId).defaultLanguage();
        return Optional.of(new NotificationContext(
                NotificationEventType.ERASURE_APPROVED,
                organizationId,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                List.of(toRecipient(submitter)),
                Instant.now(),
                locale,
                null));
    }

    /**
     * Builds the context for a behavioural anomaly (UBA, AF-383). Not query-backed: every
     * query/access field is null and the anomaly fields carry the signal. Recipients are the
     * organization's active ADMINs (mirroring {@code AI_HIGH_RISK}).
     */
    Optional<NotificationContext> buildAnomaly(UUID anomalyId, UUID organizationId) {
        var view = behaviorAnomalyLookupService.findById(organizationId, anomalyId).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        var recipients = userQueryService
                .findByOrganizationAndRole(organizationId, UserRoleType.ADMIN)
                .stream()
                .filter(UserView::active)
                .map(NotificationContextBuilder::toRecipient)
                .toList();
        var locale = localizationConfigService.getOrDefault(organizationId).defaultLanguage();
        return Optional.of(new NotificationContext(
                NotificationEventType.ANOMALY_DETECTED,
                organizationId,
                null, null, null, null, null, null, null,
                view.aiSummary(),
                view.datasourceId(),
                view.datasourceName(),
                view.userId(),
                view.userEmail(),
                view.userDisplayName(),
                null, null, null, null,
                buildAnomalyUrl(),
                recipients,
                Instant.now(),
                locale,
                null,
                anomalyId,
                view.feature(),
                view.score(),
                view.observedValue(),
                view.baselineMean(),
                anomalyUserLabel(view),
                null,
                null, null, null,
                null));
    }

    /**
     * Builds the context for the opt-in weekly dashboard digest (AF-498). Not query-backed: the single
     * recipient is the digest owner, and the digest counts ride on {@link WeeklyDigestData}. Returns
     * empty when the user no longer exists.
     */
    Optional<NotificationContext> buildWeeklyDigest(WeeklyDigestReadyEvent event) {
        var user = userQueryService.findById(event.userId()).orElse(null);
        if (user == null || !user.active()) {
            return Optional.empty();
        }
        var locale = localizationConfigService.getOrDefault(event.organizationId()).defaultLanguage();
        var digest = new WeeklyDigestData(event.weekStart(), event.weekEnd(), event.totalQueries(),
                event.pendingApprovals(), event.openAnomalies(), event.openSuggestions());
        return Optional.of(new NotificationContext(
                NotificationEventType.WEEKLY_DIGEST,
                event.organizationId(),
                null, null, null, null, null, null, null, null, null, null,
                user.id(),
                user.email(),
                user.displayName(),
                null, null, null, null,
                buildDashboardUrl(),
                List.of(toRecipient(user)),
                Instant.now(),
                locale,
                null,
                null, null, null, null, null, null,
                digest,
                null, null, null,
                null));
    }

    /**
     * Builds the context for a freshly-opened attestation campaign (AF-384). Not query-backed: the
     * campaign fields carry the signal, recipients are the eligible reviewers plus active org admins
     * resolved by the attestation module. Returns empty when the campaign no longer exists.
     */
    Optional<NotificationContext> buildAttestationCampaign(UUID campaignId, UUID organizationId) {
        var summary = attestationCampaignLookupService.findSummary(campaignId).orElse(null);
        if (summary == null) {
            return Optional.empty();
        }
        var recipientIds = attestationCampaignLookupService.findRecipientUserIds(campaignId);
        var recipients = recipientIds.stream()
                .map(userQueryService::findById)
                .flatMap(Optional::stream)
                .filter(UserView::active)
                .map(NotificationContextBuilder::toRecipient)
                .toList();
        var locale = localizationConfigService.getOrDefault(organizationId).defaultLanguage();
        return Optional.of(new NotificationContext(
                NotificationEventType.ATTESTATION_CAMPAIGN_OPENED,
                organizationId,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                buildAttestationUrl(),
                recipients,
                Instant.now(),
                locale,
                null,
                null, null, null, null, null, null,
                null,
                summary.id(), summary.name(), summary.dueAt(),
                null));
    }

    /**
     * Builds the context for an API-request notification (AF-500). Not query-backed: the connector
     * name rides in {@code datasourceName} and the call summary in {@code justification}. SUBMITTED
     * fans out to active reviewers + admins (excluding the submitter); APPROVED/EXECUTED/FAILED go to
     * the submitter, except a break-glass EXECUTED which fans out to org admins.
     */
    Optional<NotificationContext> buildApiRequest(NotificationEventType eventType, UUID apiRequestId) {
        var view = apiRequestNotificationLookupService.find(apiRequestId).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        var submitter = userQueryService.findById(view.submittedByUserId()).orElse(null);
        var recipients = apiRecipients(eventType, view);
        var locale = localizationConfigService.getOrDefault(view.organizationId()).defaultLanguage();
        return Optional.of(new NotificationContext(
                eventType,
                view.organizationId(),
                null,
                null, null, null, null,
                view.aiRiskLevel(),
                view.aiRiskScore(),
                view.aiSummary(),
                view.connectorId(),
                view.connectorName(),
                view.submittedByUserId(),
                submitter != null ? submitter.email() : null,
                submitter != null ? submitter.displayName() : null,
                view.verb() + " " + view.requestPath(),
                null, null, null,
                buildApiRequestUrl(apiRequestId),
                recipients,
                Instant.now(),
                locale,
                null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                apiRequestId));
    }

    private List<RecipientView> apiRecipients(NotificationEventType eventType, ApiRequestNotificationView view) {
        boolean breakGlassExecuted = eventType == NotificationEventType.API_REQUEST_EXECUTED
                && view.submissionReason() == com.bablsoft.accessflow.core.api.SubmissionReason.EMERGENCY_ACCESS;
        if (eventType == NotificationEventType.API_REQUEST_SUBMITTED || breakGlassExecuted) {
            var roles = breakGlassExecuted
                    ? List.of(UserRoleType.ADMIN)
                    : List.of(UserRoleType.REVIEWER, UserRoleType.ADMIN);
            return roles.stream()
                    .flatMap(r -> userQueryService.findByOrganizationAndRole(view.organizationId(), r).stream())
                    .filter(UserView::active)
                    .filter(u -> !u.id().equals(view.submittedByUserId()))
                    .distinct()
                    .map(NotificationContextBuilder::toRecipient)
                    .toList();
        }
        var submitter = userQueryService.findById(view.submittedByUserId())
                .filter(UserView::active).orElse(null);
        return submitter != null ? List.of(toRecipient(submitter)) : List.of();
    }

    private URI buildApiRequestUrl(UUID apiRequestId) {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/api-requests/" + apiRequestId);
    }

    /**
     * Builds the context for a connector-scoped OAuth2 token-failure alert (AF-500 / #506). Not
     * query-backed: the connector name rides in {@code datasourceName} and the connector id in
     * {@code datasourceId}. Recipients are the organization's active ADMINs (the connector is
     * effectively down). Returns empty when the connector no longer exists.
     */
    Optional<NotificationContext> buildApiConnector(NotificationEventType eventType, UUID connectorId) {
        var view = apiConnectorNotificationLookupService.find(connectorId).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        var recipients = userQueryService
                .findByOrganizationAndRole(view.organizationId(), UserRoleType.ADMIN)
                .stream()
                .filter(UserView::active)
                .map(NotificationContextBuilder::toRecipient)
                .toList();
        var locale = localizationConfigService.getOrDefault(view.organizationId()).defaultLanguage();
        return Optional.of(new NotificationContext(
                eventType,
                view.organizationId(),
                null, null, null, null, null, null, null, null,
                connectorId,
                view.name(),
                null, null, null, null, null, null, null,
                buildApiConnectorUrl(connectorId),
                recipients,
                Instant.now(),
                locale,
                null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null));
    }

    private URI buildApiConnectorUrl(UUID connectorId) {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/api-connectors/" + connectorId + "/settings");
    }

    private URI buildAttestationUrl() {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/reviews/attestations");
    }

    private URI buildDashboardUrl() {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/dashboard");
    }

    private static String anomalyUserLabel(BehaviorAnomalyView view) {
        if (view.userDisplayName() != null && !view.userDisplayName().isBlank()) {
            return view.userDisplayName();
        }
        return view.userEmail() != null ? view.userEmail() : view.userId().toString();
    }

    private URI buildAnomalyUrl() {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/admin/anomalies");
    }

    private List<RecipientView> reviewTimeoutRecipients(QueryRequestSnapshot snapshot,
                                                        UserView submitter) {
        var byUserId = new LinkedHashMap<UUID, RecipientView>();
        if (submitter != null) {
            byUserId.put(submitter.id(), toRecipient(submitter));
        }
        userQueryService
                .findByOrganizationAndRole(snapshot.organizationId(), UserRoleType.ADMIN)
                .stream()
                .filter(UserView::active)
                .forEach(u -> byUserId.putIfAbsent(u.id(), toRecipient(u)));
        return List.copyOf(byUserId.values());
    }

    private List<RecipientView> reviewersForLowestStage(ReviewPlanSnapshot plan,
                                                        QueryRequestSnapshot snapshot) {
        if (plan == null || plan.approvers() == null || plan.approvers().isEmpty()) {
            return List.of();
        }
        var lowestStage = plan.approvers().stream()
                .mapToInt(ApproverRule::stage)
                .min()
                .orElse(0);
        var rules = plan.approvers().stream()
                .filter(r -> r.stage() == lowestStage)
                .toList();
        var byUserId = new LinkedHashMap<UUID, RecipientView>();
        for (ApproverRule rule : rules) {
            if (rule.userId() != null) {
                userQueryService.findById(rule.userId())
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .ifPresent(u -> byUserId.putIfAbsent(u.id(), toRecipient(u)));
            } else if (rule.role() != null) {
                userQueryService.findByOrganizationAndRoleName(snapshot.organizationId(),
                                rule.role())
                        .stream()
                        .filter(UserView::active)
                        .filter(u -> !u.id().equals(snapshot.submittedByUserId()))
                        .forEach(u -> byUserId.putIfAbsent(u.id(), toRecipient(u)));
            }
        }
        return byUserId.values().stream()
                .sorted(Comparator.comparing(RecipientView::email,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private URI buildReviewUrl(UUID queryRequestId) {
        var base = properties.publicBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + "/queries/" + queryRequestId);
    }

    private static RecipientView toRecipient(UserView user) {
        return new RecipientView(user.id(), user.email(), user.displayName());
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        var stripped = text.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max) + "…";
    }
}
