package com.bablsoft.accessflow.realtime.internal;

import com.bablsoft.accessflow.access.api.AccessRequestLookupService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.ApprovalPredictionCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.events.QueryStatusChangedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupItemExecutedEvent;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupStatusChangedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.api.UserNotificationLookupService;
import com.bablsoft.accessflow.notifications.api.UserNotificationView;
import com.bablsoft.accessflow.notifications.events.UserNotificationCreatedEvent;
import com.bablsoft.accessflow.realtime.internal.ws.SessionRegistry;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import com.bablsoft.accessflow.workflow.events.ReviewDecisionMadeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeEventDispatcherTest {

    @Mock SessionRegistry sessionRegistry;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock UserQueryService userQueryService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock AiAnalysisLookupService aiAnalysisLookupService;
    @Mock UserNotificationLookupService userNotificationLookupService;
    @Mock AccessRequestLookupService accessRequestLookupService;
    @Mock AttestationCampaignLookupService attestationCampaignLookupService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-07T10:00:00Z"),
            ZoneOffset.UTC);

    private RealtimeEventDispatcher dispatcher;

    private final UUID queryId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new RealtimeEventDispatcher(sessionRegistry, objectMapper,
                queryRequestLookupService, reviewPlanLookupService, userQueryService,
                datasourceAdminService, aiAnalysisLookupService, userNotificationLookupService,
                accessRequestLookupService, attestationCampaignLookupService, clock);
    }

    @Test
    void onQueryStatusChangedSendsEnvelopeToSubmitter() throws Exception {
        var event = new QueryStatusChangedEvent(queryId, submitterId,
                QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW);

        dispatcher.onQueryStatusChanged(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("query.status_changed");
        assertThat(envelope.get("timestamp").asString()).isEqualTo("2026-05-07T10:00:00Z");
        var data = envelope.get("data");
        assertThat(data.get("query_id").asString()).isEqualTo(queryId.toString());
        assertThat(data.get("old_status").asString()).isEqualTo("PENDING_AI");
        assertThat(data.get("new_status").asString()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void onDeploymentStatusChangedSendsEnvelopeToSubmitter() throws Exception {
        var deploymentRequestId = UUID.randomUUID();
        dispatcher.onDeploymentStatusChanged(new DeploymentStatusChangedEvent(deploymentRequestId,
                submitterId, QueryStatus.PENDING_REVIEW, QueryStatus.APPROVED));

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("deployment.status_changed");
        assertThat(envelope.get("timestamp").asString()).isEqualTo("2026-05-07T10:00:00Z");
        var data = envelope.get("data");
        assertThat(data.get("deployment_request_id").asString()).isEqualTo(deploymentRequestId.toString());
        assertThat(data.get("old_status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(data.get("new_status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void onRequestGroupStatusChangedSendsEnvelopeToSubmitter() throws Exception {
        var groupId = UUID.randomUUID();
        dispatcher.onRequestGroupStatusChanged(new RequestGroupStatusChangedEvent(groupId, submitterId,
                RequestGroupStatus.PENDING_REVIEW, RequestGroupStatus.APPROVED));

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("request_group.status_changed");
        var data = envelope.get("data");
        assertThat(data.get("request_group_id").asString()).isEqualTo(groupId.toString());
        assertThat(data.get("old_status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(data.get("new_status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void onRequestGroupItemExecutedSendsStepProgressToSubmitter() throws Exception {
        var groupId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        dispatcher.onRequestGroupItemExecuted(new RequestGroupItemExecutedEvent(groupId, itemId,
                submitterId, 2, RequestGroupItemStatus.EXECUTED));

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("request_group.item_executed");
        var data = envelope.get("data");
        assertThat(data.get("item_id").asString()).isEqualTo(itemId.toString());
        assertThat(data.get("sequence_order").asInt()).isEqualTo(2);
        assertThat(data.get("status").asString()).isEqualTo("EXECUTED");
    }

    @Test
    void onQueryExecutedSendsRowsAffectedAndDuration() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        var event = new QueryExecutedEvent(queryId, 42L, 1234L, QueryStatus.EXECUTED);

        dispatcher.onQueryExecuted(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("query.executed");
        var data = envelope.get("data");
        assertThat(data.get("rows_affected").asLong()).isEqualTo(42L);
        assertThat(data.get("duration_ms").asLong()).isEqualTo(1234L);
    }

    @Test
    void onQueryExecutedRendersRowsAffectedAsNullWhenAbsent() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        var event = new QueryExecutedEvent(queryId, null, 50L, QueryStatus.FAILED);

        dispatcher.onQueryExecuted(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("data").get("rows_affected").isNull()).isTrue();
    }

    @Test
    void onQueryExecutedSilentlySkipsWhenSnapshotMissing() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        dispatcher.onQueryExecuted(new QueryExecutedEvent(queryId, null, 0L, QueryStatus.FAILED));

        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void onAiAnalysisCompletedIncludesRiskScore() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(new AiAnalysisSummaryView(
                        UUID.randomUUID(), queryId, RiskLevel.MEDIUM, 55, "ok",
                        false, null)));
        var event = new AiAnalysisCompletedEvent(queryId, UUID.randomUUID(), RiskLevel.MEDIUM);

        dispatcher.onAiAnalysisCompleted(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("ai.analysis_complete");
        assertThat(envelope.get("data").get("risk_level").asString()).isEqualTo("MEDIUM");
        assertThat(envelope.get("data").get("risk_score").asInt()).isEqualTo(55);
    }

    @Test
    void onQueryReadyForReviewFanoutToEligibleReviewers() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWithUserApprovers(reviewerId)));
        when(userQueryService.findById(reviewerId))
                .thenReturn(Optional.of(activeUser(reviewerId, "rev@example.com")));
        when(userQueryService.findById(submitterId))
                .thenReturn(Optional.of(activeUser(submitterId, "sub@example.com")));
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasourceView("orders-prod"));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(new AiAnalysisSummaryView(
                        UUID.randomUUID(), queryId, RiskLevel.HIGH, 80, "danger",
                        false, null)));

        dispatcher.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(org.mockito.ArgumentMatchers.eq(reviewerId),
                captor.capture());
        var envelope = objectMapper.readTree(captor.getValue());
        assertThat(envelope.get("event").asString()).isEqualTo("review.new_request");
        var data = envelope.get("data");
        assertThat(data.get("query_id").asString()).isEqualTo(queryId.toString());
        assertThat(data.get("risk_level").asString()).isEqualTo("HIGH");
        assertThat(data.get("submitter").asString()).isEqualTo("sub@example.com");
        assertThat(data.get("datasource").asString()).isEqualTo("orders-prod");
    }

    @Test
    void onQueryReadyForReviewSkipsWhenSubmitterIsTheOnlyApprover() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWithUserApprovers(submitterId)));
        when(userQueryService.findById(submitterId))
                .thenReturn(Optional.of(activeUser(submitterId, "self@example.com")));
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasourceView("orders"));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.empty());

        dispatcher.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        verify(sessionRegistry, never()).sendToUser(org.mockito.ArgumentMatchers.eq(submitterId),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void onReviewDecisionMadeSendsEnvelopeToSubmitter() throws Exception {
        when(userQueryService.findById(reviewerId))
                .thenReturn(Optional.of(activeUser(reviewerId, "alice@example.com")));
        var event = new ReviewDecisionMadeEvent(queryId, submitterId, reviewerId,
                DecisionType.APPROVED, "looks good");

        dispatcher.onReviewDecisionMade(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asString()).isEqualTo("review.decision_made");
        var data = envelope.get("data");
        assertThat(data.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(data.get("reviewer").asString()).isEqualTo("alice@example.com");
        assertThat(data.get("comment").asString()).isEqualTo("looks good");
    }

    @Test
    void onReviewDecisionMadeRendersNullCommentAsJsonNull() throws Exception {
        var event = new ReviewDecisionMadeEvent(queryId, submitterId, reviewerId,
                DecisionType.REQUESTED_CHANGES, null);

        dispatcher.onReviewDecisionMade(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("data").get("comment").isNull()).isTrue();
    }

    @Test
    void onUserNotificationCreatedPushesEnvelopeToRecipient() throws Exception {
        var notificationId = UUID.randomUUID();
        var recipientId = UUID.randomUUID();
        when(userNotificationLookupService.findById(notificationId))
                .thenReturn(Optional.of(new UserNotificationView(
                        notificationId, recipientId, organizationId,
                        NotificationEventType.QUERY_APPROVED, queryId, null, null,
                        "{\"datasource\":\"prod\"}", false,
                        Instant.parse("2026-05-08T09:00:00Z"), null)));

        dispatcher.onUserNotificationCreated(
                new UserNotificationCreatedEvent(notificationId, recipientId));

        var envelope = captureEnvelope(recipientId);
        assertThat(envelope.get("event").asString()).isEqualTo("notification.created");
        var data = envelope.get("data");
        assertThat(data.get("notification_id").asString()).isEqualTo(notificationId.toString());
        assertThat(data.get("event_type").asString()).isEqualTo("QUERY_APPROVED");
        assertThat(data.get("query_id").asString()).isEqualTo(queryId.toString());
        assertThat(data.get("created_at").asString()).isEqualTo("2026-05-08T09:00:00Z");
    }

    @Test
    void onUserNotificationCreatedSkipsWhenLookupMisses() {
        var notificationId = UUID.randomUUID();
        var recipientId = UUID.randomUUID();
        when(userNotificationLookupService.findById(notificationId)).thenReturn(Optional.empty());

        dispatcher.onUserNotificationCreated(
                new UserNotificationCreatedEvent(notificationId, recipientId));

        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void onUserNotificationCreatedRendersNullQueryIdAsJsonNull() throws Exception {
        var notificationId = UUID.randomUUID();
        var recipientId = UUID.randomUUID();
        when(userNotificationLookupService.findById(notificationId))
                .thenReturn(Optional.of(new UserNotificationView(
                        notificationId, recipientId, organizationId,
                        NotificationEventType.AI_HIGH_RISK, null, null, null, "{}", false,
                        Instant.parse("2026-05-08T09:00:00Z"), null)));

        dispatcher.onUserNotificationCreated(
                new UserNotificationCreatedEvent(notificationId, recipientId));

        var envelope = captureEnvelope(recipientId);
        assertThat(envelope.get("data").get("query_id").isNull()).isTrue();
    }

    @Test
    void dispatcherSwallowsLookupFailures() {
        // Lookup throws on every event but the dispatcher must still not propagate.
        when(queryRequestLookupService.findById(queryId))
                .thenThrow(new RuntimeException("db down"));

        dispatcher.onQueryExecuted(new QueryExecutedEvent(queryId, 1L, 1L, QueryStatus.EXECUTED));
        // No exception thrown — registry never called because the snapshot lookup failed.
        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void onAttestationCampaignOpenedFansOutToRecipients() throws Exception {
        var campaignId = UUID.randomUUID();
        var recipientId = UUID.randomUUID();
        when(attestationCampaignLookupService.findSummary(campaignId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.attestation.api.AttestationCampaignSummary(
                        campaignId, organizationId, "Q3 review",
                        Instant.parse("2026-07-08T00:00:00Z"))));
        when(attestationCampaignLookupService.findRecipientUserIds(campaignId))
                .thenReturn(java.util.Set.of(recipientId));

        dispatcher.onAttestationCampaignOpened(
                new com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent(
                        campaignId, organizationId));

        var envelope = captureEnvelope(recipientId);
        assertThat(envelope.get("event").asString()).isEqualTo("attestation.campaign_opened");
        var data = envelope.get("data");
        assertThat(data.get("campaign_id").asString()).isEqualTo(campaignId.toString());
        assertThat(data.get("name").asString()).isEqualTo("Q3 review");
    }

    @Test
    void onAttestationCampaignOpenedSkipsWhenCampaignMissing() {
        var campaignId = UUID.randomUUID();
        when(attestationCampaignLookupService.findSummary(campaignId)).thenReturn(Optional.empty());
        dispatcher.onAttestationCampaignOpened(
                new com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent(
                        campaignId, organizationId));
        verifyNoInteractions(sessionRegistry);
    }

    /**
     * AF-645: the prediction is withheld from the submitter (QueryReadController nulls the block
     * out for them), so pushing them a refetch trigger would only make them refetch nothing.
     */
    @Test
    void onApprovalPredictionCompletedReachesEligibleReviewersButNotTheSubmitter() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWithUserApprovers(reviewerId)));
        when(userQueryService.findById(reviewerId))
                .thenReturn(Optional.of(activeUser(reviewerId, "rev@example.com")));

        dispatcher.onApprovalPredictionCompleted(new ApprovalPredictionCompletedEvent(
                queryId, UUID.randomUUID(), 0.78));

        var reviewerEnvelope = captureEnvelope(reviewerId);
        assertThat(reviewerEnvelope.get("event").asString()).isEqualTo("query.prediction_complete");
        assertThat(reviewerEnvelope.get("data").get("query_id").asString())
                .isEqualTo(queryId.toString());
        assertThat(reviewerEnvelope.get("data").get("probability").asDouble()).isEqualTo(0.78);
        verify(sessionRegistry, never()).sendToUser(org.mockito.ArgumentMatchers.eq(submitterId),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** Skipped and failed sentinel rows still notify — the client refetches and renders the reason. */
    @Test
    void onApprovalPredictionCompletedRendersJsonNullForASentinelRow() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(reviewPlanLookupService.findForDatasource(datasourceId))
                .thenReturn(Optional.of(planWithUserApprovers(reviewerId)));
        when(userQueryService.findById(reviewerId))
                .thenReturn(Optional.of(activeUser(reviewerId, "rev@example.com")));

        dispatcher.onApprovalPredictionCompleted(new ApprovalPredictionCompletedEvent(
                queryId, UUID.randomUUID(), null));

        var envelope = captureEnvelope(reviewerId);
        assertThat(envelope.get("data").get("probability").isNull()).isTrue();
    }

    /**
     * With the submitter removed from the recipient set, a datasource with no review plan has no
     * recipients at all — previously the submitter was the fallback, so nothing covered this.
     */
    @Test
    void onApprovalPredictionCompletedSendsToNobodyWhenTheDatasourceHasNoReviewPlan() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());

        dispatcher.onApprovalPredictionCompleted(new ApprovalPredictionCompletedEvent(
                queryId, UUID.randomUUID(), 0.42));

        verify(sessionRegistry, never()).sendToUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void onApprovalPredictionCompletedSkipsWhenQueryIsGone() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        dispatcher.onApprovalPredictionCompleted(new ApprovalPredictionCompletedEvent(
                queryId, UUID.randomUUID(), 0.5));

        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void onApprovalPredictionCompletedSwallowsLookupFailures() {
        when(queryRequestLookupService.findById(queryId))
                .thenThrow(new IllegalStateException("db down"));

        dispatcher.onApprovalPredictionCompleted(new ApprovalPredictionCompletedEvent(
                queryId, UUID.randomUUID(), 0.5));

        verifyNoInteractions(sessionRegistry);
    }

    private JsonNode captureEnvelope(UUID userId) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(org.mockito.ArgumentMatchers.eq(userId),
                captor.capture());
        return objectMapper.readTree(captor.getValue());
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_REVIEW, null,
                null, null, false);
    }

    private ReviewPlanSnapshot planWithUserApprovers(UUID... approverUserIds) {
        var approvers = java.util.Arrays.stream(approverUserIds)
                .map(uid -> new ApproverRule(uid, null, 1))
                .toList();
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, true, true,
                1, false, 1, approvers, List.of());
    }

    private UserView activeUser(UUID id, String email) {
        return new UserView(id, email, "Display " + email, UserRoleType.REVIEWER,
                organizationId, true, AuthProviderType.LOCAL, null,
                Instant.now(), null, false, Instant.now());
    }

    private DatasourceView datasourceView(String name) {
        return new DatasourceView(datasourceId, organizationId, name, DbType.POSTGRESQL,
                "localhost", 5432, "db", "user", SslMode.DISABLE, 5, 1000,
                false, true, null, true, null, false, null, null, null,
                null, null, true, Instant.now());
    }
}
