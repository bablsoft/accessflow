package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.bablsoft.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private NotificationContextBuilder contextBuilder;
    private NotificationChannelRepository channelRepository;
    private UserNotificationService userNotificationService;
    private NotificationChannelStrategy emailStrategy;
    private NotificationChannelStrategy webhookStrategy;
    private SystemEmailFallback systemEmailFallback;
    private NotificationDispatcher dispatcher;
    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID queryRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contextBuilder = mock(NotificationContextBuilder.class);
        channelRepository = mock(NotificationChannelRepository.class);
        userNotificationService = mock(UserNotificationService.class);
        emailStrategy = mock(NotificationChannelStrategy.class);
        when(emailStrategy.supports()).thenReturn(NotificationChannelType.EMAIL);
        webhookStrategy = mock(NotificationChannelStrategy.class);
        when(webhookStrategy.supports()).thenReturn(NotificationChannelType.WEBHOOK);
        systemEmailFallback = mock(SystemEmailFallback.class);
        dispatcher = new NotificationDispatcher(contextBuilder, channelRepository,
                userNotificationService, new ObjectMapper(), systemEmailFallback,
                List.of(emailStrategy, webhookStrategy));
    }

    @Test
    void unknownQueryShortCircuits() {
        when(contextBuilder.build(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(channelRepository, never())
                .findAllByOrganizationIdAndIdInAndActiveTrue(any(), any());
        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void noChannelsConfiguredSkips() {
        whenContextBuilds();
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(channelRepository, never())
                .findAllByOrganizationIdAndIdInAndActiveTrue(any(), any());
        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void planChannelsRoutedToMatchingStrategies() {
        whenContextBuilds();
        var emailCh = channel(NotificationChannelType.EMAIL);
        var webhookCh = channel(NotificationChannelType.WEBHOOK);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId(), webhookCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh, webhookCh));

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(webhookStrategy).deliver(any(), eq(webhookCh));
    }

    @Test
    void aiHighRiskUsesAllActiveChannelsForOrg() {
        whenContextBuilds();
        var slackCh = channel(NotificationChannelType.SLACK);
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(channelRepository.findAllByOrganizationIdAndActiveTrue(orgId))
                .thenReturn(List.of(slackCh, emailCh));

        dispatcher.dispatch(NotificationEventType.AI_HIGH_RISK, queryRequestId, null, null, null);

        // Slack strategy isn't registered in this test so it's skipped silently.
        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(channelRepository, never())
                .findAllByOrganizationIdAndIdInAndActiveTrue(any(), any());
    }

    @Test
    void perChannelExceptionDoesNotPoisonOthers() {
        whenContextBuilds();
        var emailCh = channel(NotificationChannelType.EMAIL);
        var webhookCh = channel(NotificationChannelType.WEBHOOK);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId(), webhookCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh, webhookCh));
        doThrow(new RuntimeException("boom")).when(emailStrategy).deliver(any(), any());

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(webhookStrategy).deliver(any(), eq(webhookCh));
    }

    @Test
    void unknownStrategyTypeIsSkipped() {
        // Build a dispatcher with NO registered strategies.
        var emptyDispatcher = new NotificationDispatcher(contextBuilder, channelRepository,
                userNotificationService, new ObjectMapper(), systemEmailFallback, List.of());
        whenContextBuilds();
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        emptyDispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void systemEmailFallbackInvokedWhenNoEmailChannel() {
        var reviewer = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_APPROVED,
                        List.of(new RecipientView(reviewer, "a@example.com", "A")))));
        var webhookCh = channel(NotificationChannelType.WEBHOOK);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(webhookCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(webhookCh));

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(systemEmailFallback).deliverIfPossible(any());
        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void systemEmailFallbackSkippedWhenEmailChannelDelivers() {
        var reviewer = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_APPROVED,
                        List.of(new RecipientView(reviewer, "a@example.com", "A")))));
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(systemEmailFallback, never()).deliverIfPossible(any());
    }

    @Test
    void systemEmailFallbackSkippedWhenEventHasNoTemplate() {
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.TEST,
                        List.of(new RecipientView(UUID.randomUUID(), "a@example.com", "A")))));
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.TEST, queryRequestId, null, null, null);

        verify(systemEmailFallback, never()).deliverIfPossible(any());
    }

    @Test
    void persistsInAppNotificationsForReviewers() {
        var reviewerA = UUID.randomUUID();
        var reviewerB = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_SUBMITTED,
                        List.of(new RecipientView(reviewerA, "a@x", "A"),
                                new RecipientView(reviewerB, "b@x", "B")))));
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.QUERY_SUBMITTED, queryRequestId, null, null, null);

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.QUERY_SUBMITTED),
                eq(Set.of(reviewerA, reviewerB)),
                eq(orgId),
                eq(queryRequestId),
                isNull(),
                isNull(),
                any());
    }

    @Test
    void persistsApiRequestNotificationWithApiRequestId() {
        var reviewer = UUID.randomUUID();
        var apiRequestId = UUID.randomUUID();
        when(contextBuilder.buildApiRequest(
                eq(NotificationEventType.API_REQUEST_SUBMITTED), eq(apiRequestId)))
                .thenReturn(Optional.of(sampleApiContext(apiRequestId,
                        List.of(new RecipientView(reviewer, "a@x", "A")))));
        when(contextBuilder.lookupPlanChannelIds(any())).thenReturn(List.of());

        dispatcher.dispatchApiRequest(NotificationEventType.API_REQUEST_SUBMITTED, apiRequestId);

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.API_REQUEST_SUBMITTED),
                eq(Set.of(reviewer)),
                eq(orgId),
                isNull(),
                eq(apiRequestId),
                isNull(),
                any());
    }

    @Test
    void persistsDeploymentNotificationWithDeploymentRequestIdAndPayload() {
        var reviewer = UUID.randomUUID();
        var deploymentRequestId = UUID.randomUUID();
        when(contextBuilder.buildDeployment(
                eq(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED), eq(deploymentRequestId),
                eq(com.bablsoft.accessflow.deploygov.api.DeploymentOutcome.ROLLED_BACK), isNull()))
                .thenReturn(Optional.of(sampleDeploymentContext(
                        NotificationEventType.DEPLOYMENT_OUTCOME_FAILED, deploymentRequestId,
                        com.bablsoft.accessflow.deploygov.api.DeploymentOutcome.ROLLED_BACK,
                        List.of(new RecipientView(reviewer, "a@x", "A")))));
        when(channelRepository.findAllByOrganizationIdAndActiveTrue(orgId)).thenReturn(List.of());

        dispatcher.dispatchDeployment(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                deploymentRequestId,
                com.bablsoft.accessflow.deploygov.api.DeploymentOutcome.ROLLED_BACK, null);

        var payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED),
                eq(Set.of(reviewer)),
                eq(orgId),
                isNull(),
                isNull(),
                eq(deploymentRequestId),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains("\"deployment_id\":\"" + deploymentRequestId + "\"")
                .contains("\"environment\":\"production\"")
                .contains("\"version\":\"2.4.1\"")
                .contains("\"outcome\":\"ROLLED_BACK\"")
                .contains("\"datasource\":\"payments-pipeline\"");
    }

    /**
     * #695: deployment pipelines carry no review-plan channel binding, so every deployment event
     * must fan out org-wide. Falling through to the plan-channel lookup would silently deliver to
     * zero channels (null datasource → empty plan list).
     */
    @Test
    void deploymentEventsUseAllActiveChannelsForOrg() {
        var deploymentRequestId = UUID.randomUUID();
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(channelRepository.findAllByOrganizationIdAndActiveTrue(orgId))
                .thenReturn(List.of(emailCh));
        for (var eventType : List.of(NotificationEventType.DEPLOYMENT_SUBMITTED,
                NotificationEventType.DEPLOYMENT_APPROVED,
                NotificationEventType.DEPLOYMENT_REJECTED,
                NotificationEventType.DEPLOYMENT_OUTCOME_FAILED,
                NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED)) {
            when(contextBuilder.buildDeployment(eq(eventType), eq(deploymentRequestId), any(),
                    any()))
                    .thenReturn(Optional.of(sampleDeploymentContext(eventType, deploymentRequestId,
                            null, List.of(new RecipientView(UUID.randomUUID(), "a@x", "A")))));

            dispatcher.dispatchDeployment(eventType, deploymentRequestId, null, null);
        }
        verify(emailStrategy, org.mockito.Mockito.times(5)).deliver(any(), eq(emailCh));
        verify(contextBuilder, never()).lookupPlanChannelIds(any());
    }

    @Test
    void unknownDeploymentRequestShortCircuits() {
        when(contextBuilder.buildDeployment(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        dispatcher.dispatchDeployment(NotificationEventType.DEPLOYMENT_APPROVED,
                UUID.randomUUID(), null, null);

        verify(channelRepository, never()).findAllByOrganizationIdAndActiveTrue(any());
        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void skipsTestEventForInAppPersistence() {
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.TEST,
                        List.of(new RecipientView(UUID.randomUUID(), "x@x", "X")))));
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.TEST, queryRequestId, null, null, null);

        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistenceFailureIsSwallowed() {
        var reviewer = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_APPROVED,
                        List.of(new RecipientView(reviewer, "a@x", "A")))));
        doThrow(new RuntimeException("db down"))
                .when(userNotificationService).recordForUsers(any(), any(), any(), any(), any(), any(), any());
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null, null);

        // Channels still receive the event even though in-app persistence failed.
        verify(emailStrategy).deliver(any(), eq(emailCh));
    }

    private void whenContextBuilds() {
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any(), any()))
                .thenReturn(Optional.of(sampleContext()));
    }

    @Test
    void dispatchQueryExecutedRoutesViaPlanChannelsAndPersistsOutcome() {
        var submitter = UUID.randomUUID();
        when(contextBuilder.buildQueryExecuted(queryRequestId, QueryStatus.EXECUTED, 5L, 120L))
                .thenReturn(Optional.of(sampleExecutedContext(
                        List.of(new RecipientView(submitter, "a@x", "A")))));
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        dispatcher.dispatchQueryExecuted(queryRequestId, QueryStatus.EXECUTED, 5L, 120L);

        // Plan channels, not the org-wide list — QUERY_EXECUTED is submitter-targeted.
        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(channelRepository, never()).findAllByOrganizationIdAndActiveTrue(any());
        var payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.QUERY_EXECUTED),
                eq(Set.of(submitter)),
                eq(orgId),
                eq(queryRequestId),
                isNull(),
                isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains("\"final_status\":\"EXECUTED\"")
                .contains("\"rows_affected\":5");
    }

    @Test
    void dispatchQueryExecutedUnknownQueryShortCircuits() {
        when(contextBuilder.buildQueryExecuted(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        dispatcher.dispatchQueryExecuted(queryRequestId, QueryStatus.EXECUTED, 5L, 120L);

        verify(emailStrategy, never()).deliver(any(), any());
        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchApiConnectorUsesAllActiveOrgChannels() {
        var connectorId = UUID.randomUUID();
        when(contextBuilder.buildApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, List.of())));
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(channelRepository.findAllByOrganizationIdAndActiveTrue(orgId)).thenReturn(List.of(emailCh));

        dispatcher.dispatchApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId);

        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(channelRepository, never())
                .findAllByOrganizationIdAndIdInAndActiveTrue(any(), any());
    }

    @Test
    void dispatchApiConnectorUnknownShortCircuits() {
        var connectorId = UUID.randomUUID();
        when(contextBuilder.buildApiConnector(any(), eq(connectorId))).thenReturn(Optional.empty());

        dispatcher.dispatchApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId);

        verify(emailStrategy, never()).deliver(any(), any());
    }

    private NotificationContext sampleContextWithRecipients(NotificationEventType type,
                                                            List<RecipientView> recipients) {
        return new NotificationContext(
                type,
                orgId, queryRequestId, QueryType.SELECT,
                "SELECT 1", "SELECT 1", "SELECT 1",
                RiskLevel.LOW, 10, "ok",
                datasourceId, "ds",
                UUID.randomUUID(), "submit@example.com", "Sub",
                null, null, null, null,
                URI.create("https://app.example.test/queries/x"),
                recipients, Instant.now(), "en", null);
    }

    /** A QUERY_EXECUTED context (#627) carrying the execution outcome in the trailing fields. */
    private NotificationContext sampleDeploymentContext(
            NotificationEventType type, UUID deploymentRequestId,
            com.bablsoft.accessflow.deploygov.api.DeploymentOutcome outcome,
            List<RecipientView> recipients) {
        return new NotificationContext(
                type,
                orgId,
                null,
                null, null, null, null,
                null, null, null,
                UUID.randomUUID(), "payments-pipeline",
                UUID.randomUUID(), "submit@example.com", "Sub",
                null,
                null, null, null,
                null,
                recipients, Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                null, null, null,
                null, null, null,
                null, null, null,
                deploymentRequestId, "production", "2.4.1", outcome, null);
    }

    private NotificationContext sampleExecutedContext(List<RecipientView> recipients) {
        return new NotificationContext(
                NotificationEventType.QUERY_EXECUTED,
                orgId, queryRequestId, QueryType.SELECT,
                "SELECT 1", "SELECT 1", "SELECT 1",
                RiskLevel.LOW, 10, "ok",
                datasourceId, "ds",
                UUID.randomUUID(), "submit@example.com", "Sub",
                null, null, null, null,
                URI.create("https://app.example.test/queries/x"),
                recipients, Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                null,
                QueryStatus.EXECUTED, 5L, 120L);
    }

    private NotificationContext sampleApiContext(UUID apiRequestId, List<RecipientView> recipients) {
        return new NotificationContext(
                NotificationEventType.API_REQUEST_SUBMITTED,
                orgId, null, null,
                null, null, null,
                null, null, null,
                datasourceId, "conn",
                UUID.randomUUID(), "submit@example.com", "Sub",
                "GET /things", null, null, null,
                URI.create("https://app.example.test/api-requests/x"),
                recipients, Instant.now(), "en", null,
                null, null, null, null, null, null,
                null,
                null, null, null,
                apiRequestId);
    }

    private NotificationChannelEntity channel(NotificationChannelType type) {
        var c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setChannelType(type);
        c.setName(type.name());
        c.setActive(true);
        c.setConfigJson("{}");
        c.setCreatedAt(Instant.now());
        return c;
    }

    private NotificationContext sampleContext() {
        return new NotificationContext(
                NotificationEventType.QUERY_APPROVED,
                orgId, queryRequestId, QueryType.SELECT,
                "SELECT 1", "SELECT 1", "SELECT 1",
                RiskLevel.LOW, 10, "ok",
                datasourceId, "ds",
                UUID.randomUUID(), "submit@example.com", "Sub",
                null, null, null, null,
                URI.create("https://app.example.test/queries/x"),
                List.of(), Instant.now(), "en", null);
    }
}
