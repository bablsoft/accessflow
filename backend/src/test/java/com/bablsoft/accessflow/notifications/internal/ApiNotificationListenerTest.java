package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.apigov.events.ApiConnectorTokenFailureEvent;
import com.bablsoft.accessflow.apigov.events.ApiConnectorUnhealthyEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestReadyForReviewEvent;
import com.bablsoft.accessflow.apigov.events.ApiSchemaDriftDetectedEvent;
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
                NotificationEventType.API_CONNECTOR_OAUTH2_TOKEN_FAILED, connectorId, null);
    }

    @Test
    void schemaDriftNotifiesDriftEventWithFindingCount() {
        var connectorId = UUID.randomUUID();
        listener.onSchemaDriftDetected(new ApiSchemaDriftDetectedEvent(UUID.randomUUID(), connectorId,
                UUID.randomUUID(), UUID.randomUUID(), 3));
        verify(dispatcher).dispatchApiConnector(NotificationEventType.API_CONNECTOR_DRIFT, connectorId, "3");
    }

    @Test
    void unhealthyNotifiesUnhealthyEventWithLastError() {
        var connectorId = UUID.randomUUID();
        listener.onConnectorUnhealthy(new ApiConnectorUnhealthyEvent(connectorId, UUID.randomUUID(), 5,
                "Connection refused"));
        verify(dispatcher).dispatchApiConnector(NotificationEventType.API_CONNECTOR_UNHEALTHY, connectorId,
                "Connection refused");
    }

    @Test
    void budgetExceededNotifiesWithSpendAgainstBudgetInUnits() {
        var connectorId = UUID.randomUUID();
        listener.onConnectorBudgetExceeded(new com.bablsoft.accessflow.apigov.events.ApiConnectorBudgetExceededEvent(
                connectorId, UUID.randomUUID(), 12_500_000L, 10_000_000L));
        verify(dispatcher).dispatchApiConnector(NotificationEventType.API_CONNECTOR_BUDGET_EXCEEDED,
                connectorId, "12.5 / 10");
    }

    @Test
    void unitsRenderMicrosAsAPlainDecimal() {
        org.assertj.core.api.Assertions.assertThat(ApiNotificationListener.units(0)).isEqualTo("0");
        org.assertj.core.api.Assertions.assertThat(ApiNotificationListener.units(1)).isEqualTo("0.000001");
        org.assertj.core.api.Assertions.assertThat(ApiNotificationListener.units(250_000_000L)).isEqualTo("250");
    }

    @Test
    void rejectedIsNotNotified() {
        listener.onDecided(new ApiRequestDecidedEvent(requestId, QueryStatus.REJECTED, null));
        verify(dispatcher, never()).dispatchApiRequest(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
