package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.config.NotificationsProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import com.bablsoft.accessflow.notifications.internal.push.WebPushMessage;
import com.bablsoft.accessflow.notifications.internal.push.WebPushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebPushNotificationListenerTest {

    private NotificationContextBuilder contextBuilder;
    private PushSubscriptionRepository subscriptionRepository;
    private WebPushSender webPushSender;
    private MessageSource messageSource;
    private WebPushNotificationListener listener;

    private final UUID queryId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contextBuilder = mock(NotificationContextBuilder.class);
        subscriptionRepository = mock(PushSubscriptionRepository.class);
        webPushSender = mock(WebPushSender.class);
        messageSource = mock(MessageSource.class);
        var properties = new NotificationsProperties(URI.create("https://app.acme.test"), null, null,
                null);
        listener = new WebPushNotificationListener(contextBuilder, subscriptionRepository,
                webPushSender, properties, messageSource);
        when(messageSource.getMessage(eq("push.review_request.title"), any(), any()))
                .thenReturn("Review request · prod-db");
        when(messageSource.getMessage(eq("push.review_request.body"), any(), any()))
                .thenReturn("Sub is requesting approval");
    }

    private NotificationContext context(List<RecipientView> recipients) {
        return new NotificationContext(NotificationEventType.QUERY_SUBMITTED, UUID.randomUUID(),
                queryId, QueryType.SELECT, "SELECT 1", "SELECT 1", "SELECT 1", null, null, null,
                UUID.randomUUID(), "prod-db", UUID.randomUUID(), "sub@acme.test", "Sub", null,
                null, null, null, URI.create("https://app.acme.test/queries/" + queryId), recipients,
                Instant.parse("2026-06-22T00:00:00Z"), "en", null);
    }

    @Test
    void pushesDecideDeepLinkToEligibleReviewerSubscriptions() {
        var recipient = new RecipientView(reviewerId, "rev@acme.test", "Rev");
        when(contextBuilder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null))
                .thenReturn(Optional.of(context(List.of(recipient))));
        var sub = new PushSubscriptionEntity();
        sub.setUserId(reviewerId);
        when(subscriptionRepository.findByUserIdIn(List.of(reviewerId))).thenReturn(List.of(sub));

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        var captor = ArgumentCaptor.forClass(WebPushMessage.class);
        verify(webPushSender).sendAll(eq(List.of(sub)), captor.capture());
        assertThat(captor.getValue().url())
                .isEqualTo("https://app.acme.test/reviews/" + queryId + "/decide");
        assertThat(captor.getValue().queryId()).isEqualTo(queryId);
        assertThat(captor.getValue().body()).contains("SELECT 1");
    }

    @Test
    void skipsWhenContextMissing() {
        when(contextBuilder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null))
                .thenReturn(Optional.empty());

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        verifyNoInteractions(subscriptionRepository, webPushSender);
    }

    @Test
    void skipsWhenNoRecipients() {
        when(contextBuilder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null))
                .thenReturn(Optional.of(context(List.of())));

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        verifyNoInteractions(subscriptionRepository, webPushSender);
    }

    @Test
    void skipsWhenNoSubscriptions() {
        var recipient = new RecipientView(reviewerId, "rev@acme.test", "Rev");
        when(contextBuilder.build(NotificationEventType.QUERY_SUBMITTED, queryId, null, null, null))
                .thenReturn(Optional.of(context(List.of(recipient))));
        when(subscriptionRepository.findByUserIdIn(List.of(reviewerId))).thenReturn(List.of());

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        verifyNoInteractions(webPushSender);
    }
}
