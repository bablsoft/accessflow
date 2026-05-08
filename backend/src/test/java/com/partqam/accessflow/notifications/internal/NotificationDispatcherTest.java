package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
        dispatcher = new NotificationDispatcher(contextBuilder, channelRepository,
                userNotificationService, new ObjectMapper(),
                List.of(emailStrategy, webhookStrategy));
    }

    @Test
    void unknownQueryShortCircuits() {
        when(contextBuilder.build(any(), any(), any(), any())).thenReturn(Optional.empty());

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

        verify(channelRepository, never())
                .findAllByOrganizationIdAndIdInAndActiveTrue(any(), any());
        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void noChannelsConfiguredSkips() {
        whenContextBuilds();
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

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

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

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

        dispatcher.dispatch(NotificationEventType.AI_HIGH_RISK, queryRequestId, null, null);

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

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

        verify(emailStrategy).deliver(any(), eq(emailCh));
        verify(webhookStrategy).deliver(any(), eq(webhookCh));
    }

    @Test
    void unknownStrategyTypeIsSkipped() {
        // Build a dispatcher with NO registered strategies.
        var emptyDispatcher = new NotificationDispatcher(contextBuilder, channelRepository,
                userNotificationService, new ObjectMapper(), List.of());
        whenContextBuilds();
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId))
                .thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        emptyDispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

        verify(emailStrategy, never()).deliver(any(), any());
    }

    @Test
    void persistsInAppNotificationsForReviewers() {
        var reviewerA = UUID.randomUUID();
        var reviewerB = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_SUBMITTED,
                        List.of(new RecipientView(reviewerA, "a@x", "A"),
                                new RecipientView(reviewerB, "b@x", "B")))));
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.QUERY_SUBMITTED, queryRequestId, null, null);

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.QUERY_SUBMITTED),
                eq(Set.of(reviewerA, reviewerB)),
                eq(orgId),
                eq(queryRequestId),
                any());
    }

    @Test
    void skipsTestEventForInAppPersistence() {
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.TEST,
                        List.of(new RecipientView(UUID.randomUUID(), "x@x", "X")))));
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of());

        dispatcher.dispatch(NotificationEventType.TEST, queryRequestId, null, null);

        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any());
    }

    @Test
    void persistenceFailureIsSwallowed() {
        var reviewer = UUID.randomUUID();
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any()))
                .thenReturn(Optional.of(sampleContextWithRecipients(
                        NotificationEventType.QUERY_APPROVED,
                        List.of(new RecipientView(reviewer, "a@x", "A")))));
        doThrow(new RuntimeException("db down"))
                .when(userNotificationService).recordForUsers(any(), any(), any(), any(), any());
        var emailCh = channel(NotificationChannelType.EMAIL);
        when(contextBuilder.lookupPlanChannelIds(datasourceId)).thenReturn(List.of(emailCh.getId()));
        when(channelRepository.findAllByOrganizationIdAndIdInAndActiveTrue(eq(orgId), anyCollection()))
                .thenReturn(List.of(emailCh));

        dispatcher.dispatch(NotificationEventType.QUERY_APPROVED, queryRequestId, null, null);

        // Channels still receive the event even though in-app persistence failed.
        verify(emailStrategy).deliver(any(), eq(emailCh));
    }

    private void whenContextBuilds() {
        when(contextBuilder.build(any(), eq(queryRequestId), any(), any()))
                .thenReturn(Optional.of(sampleContext()));
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
                recipients, Instant.now());
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
                List.of(), Instant.now());
    }
}
