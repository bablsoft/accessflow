package com.partqam.accessflow.realtime.internal;

import com.partqam.accessflow.core.api.AiAnalysisLookupService;
import com.partqam.accessflow.core.api.AiAnalysisSummaryView;
import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DatasourceView;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import com.partqam.accessflow.core.events.QueryStatusChangedEvent;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.api.UserNotificationLookupService;
import com.partqam.accessflow.notifications.api.UserNotificationView;
import com.partqam.accessflow.notifications.events.UserNotificationCreatedEvent;
import com.partqam.accessflow.realtime.internal.ws.SessionRegistry;
import com.partqam.accessflow.workflow.events.QueryExecutedEvent;
import com.partqam.accessflow.workflow.events.ReviewDecisionMadeEvent;
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
                clock);
    }

    @Test
    void onQueryStatusChangedSendsEnvelopeToSubmitter() throws Exception {
        var event = new QueryStatusChangedEvent(queryId, submitterId,
                QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW);

        dispatcher.onQueryStatusChanged(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asText()).isEqualTo("query.status_changed");
        assertThat(envelope.get("timestamp").asText()).isEqualTo("2026-05-07T10:00:00Z");
        var data = envelope.get("data");
        assertThat(data.get("query_id").asText()).isEqualTo(queryId.toString());
        assertThat(data.get("old_status").asText()).isEqualTo("PENDING_AI");
        assertThat(data.get("new_status").asText()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void onQueryExecutedSendsRowsAffectedAndDuration() throws Exception {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        var event = new QueryExecutedEvent(queryId, 42L, 1234L, QueryStatus.EXECUTED);

        dispatcher.onQueryExecuted(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asText()).isEqualTo("query.executed");
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
                        UUID.randomUUID(), queryId, RiskLevel.MEDIUM, 55, "ok")));
        var event = new AiAnalysisCompletedEvent(queryId, UUID.randomUUID(), RiskLevel.MEDIUM);

        dispatcher.onAiAnalysisCompleted(event);

        var envelope = captureEnvelope(submitterId);
        assertThat(envelope.get("event").asText()).isEqualTo("ai.analysis_complete");
        assertThat(envelope.get("data").get("risk_level").asText()).isEqualTo("MEDIUM");
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
                        UUID.randomUUID(), queryId, RiskLevel.HIGH, 80, "danger")));

        dispatcher.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(org.mockito.ArgumentMatchers.eq(reviewerId),
                captor.capture());
        var envelope = objectMapper.readTree(captor.getValue());
        assertThat(envelope.get("event").asText()).isEqualTo("review.new_request");
        var data = envelope.get("data");
        assertThat(data.get("query_id").asText()).isEqualTo(queryId.toString());
        assertThat(data.get("risk_level").asText()).isEqualTo("HIGH");
        assertThat(data.get("submitter").asText()).isEqualTo("sub@example.com");
        assertThat(data.get("datasource").asText()).isEqualTo("orders-prod");
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
        assertThat(envelope.get("event").asText()).isEqualTo("review.decision_made");
        var data = envelope.get("data");
        assertThat(data.get("decision").asText()).isEqualTo("APPROVED");
        assertThat(data.get("reviewer").asText()).isEqualTo("alice@example.com");
        assertThat(data.get("comment").asText()).isEqualTo("looks good");
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
                        NotificationEventType.QUERY_APPROVED, queryId,
                        "{\"datasource\":\"prod\"}", false,
                        Instant.parse("2026-05-08T09:00:00Z"), null)));

        dispatcher.onUserNotificationCreated(
                new UserNotificationCreatedEvent(notificationId, recipientId));

        var envelope = captureEnvelope(recipientId);
        assertThat(envelope.get("event").asText()).isEqualTo("notification.created");
        var data = envelope.get("data");
        assertThat(data.get("notification_id").asText()).isEqualTo(notificationId.toString());
        assertThat(data.get("event_type").asText()).isEqualTo("QUERY_APPROVED");
        assertThat(data.get("query_id").asText()).isEqualTo(queryId.toString());
        assertThat(data.get("created_at").asText()).isEqualTo("2026-05-08T09:00:00Z");
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
                        NotificationEventType.AI_HIGH_RISK, null, "{}", false,
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

    private JsonNode captureEnvelope(UUID userId) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(org.mockito.ArgumentMatchers.eq(userId),
                captor.capture());
        return objectMapper.readTree(captor.getValue());
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_REVIEW);
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
                Instant.now(), null, Instant.now());
    }

    private DatasourceView datasourceView(String name) {
        return new DatasourceView(datasourceId, organizationId, name, DbType.POSTGRESQL,
                "localhost", 5432, "db", "user", SslMode.DISABLE, 5, 1000,
                false, true, null, true, true, Instant.now());
    }
}
