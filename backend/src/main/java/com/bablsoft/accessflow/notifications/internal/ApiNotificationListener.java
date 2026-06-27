package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestReadyForReviewEvent;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans out API-governance notifications (AF-500): a ready-for-review request alerts reviewers/admins;
 * an approved/executed/failed request alerts the submitter (break-glass executions alert admins).
 * Delivery is best-effort and never blocks the request flow.
 */
@Component
@RequiredArgsConstructor
class ApiNotificationListener {

    private final NotificationDispatcher dispatcher;

    @ApplicationModuleListener
    void onReadyForReview(ApiRequestReadyForReviewEvent event) {
        dispatcher.dispatchApiRequest(NotificationEventType.API_REQUEST_SUBMITTED, event.apiRequestId());
    }

    @ApplicationModuleListener
    void onDecided(ApiRequestDecidedEvent event) {
        var type = mapStatus(event.status());
        if (type != null) {
            dispatcher.dispatchApiRequest(type, event.apiRequestId());
        }
    }

    private static NotificationEventType mapStatus(QueryStatus status) {
        return switch (status) {
            case APPROVED -> NotificationEventType.API_REQUEST_APPROVED;
            case EXECUTED -> NotificationEventType.API_REQUEST_EXECUTED;
            case FAILED -> NotificationEventType.API_REQUEST_FAILED;
            default -> null;
        };
    }
}
