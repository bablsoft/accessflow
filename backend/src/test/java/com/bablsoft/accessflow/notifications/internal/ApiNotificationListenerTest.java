package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.apigov.events.ApiConnectorTokenFailureEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestReadyForReviewEvent;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApiNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    private ApiNotificationListener listener;

    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ApiNotificationListener(dispatcher);
    }

    @Test
    void readyForReviewNotifiesSubmittedEvent() {
        listener.onReadyForReview(new ApiRequestReadyForReviewEvent(requestId, 1));
        verify(dispatcher).dispatchApiRequest(NotificationEventType.API_REQUEST_SUBMITTED, requestId);
    }

    @Test
    void approvedMapsToApprovedEvent() {
        listener.onDecided(new ApiRequestDecidedEvent(requestId, QueryStatus.APPROVED, null));
        verify(dispatcher).dispatchApiRequest(NotificationEventType.API_REQUEST_APPROVED, requestId);
    }

    @Test
    void executedMapsToExecutedEvent() {
        listener.onDecided(new ApiRequestDecidedEvent(requestId, QueryStatus.EXECUTED, null));
        verify(dispatcher).dispatchApiRequest(NotificationEventType.API_REQUEST_EXECUTED, requestId);
    }

    @Test
    void failedMapsToFailedEvent() {
        listener.onDecided(new ApiRequestDecidedEvent(requestId, QueryStatus.FAILED, "boom"));
        verify(dispatcher).dispatchApiRequest(NotificationEventType.API_REQUEST_FAILED, requestId);
    }

    @Test
    void connectorTokenFailureNotifiesConnectorEvent() {
        var connectorId = UUID.randomUUID();
        listener.onConnectorTokenFailure(new ApiConnectorTokenFailureEvent(connectorId, UUID.randomUUID()));
        verify(dispatcher).dispatchApiConnector(
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId);
    }

    @Test
    void rejectedIsNotNotified() {
        listener.onDecided(new ApiRequestDecidedEvent(requestId, QueryStatus.REJECTED, null));
        verify(dispatcher, never()).dispatchApiRequest(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
