package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.access.api.AccessRequestLookupService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.events.AccessGrantExpiredEvent;
import com.bablsoft.accessflow.access.events.AccessGrantRevokedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestApprovedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestRejectedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestSubmittedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Records in-app notifications for JIT access-request events: reviewers when a request is submitted,
 * and the requester on approval / rejection / expiry / revocation. Each persisted notification also
 * triggers a {@code notification.created} WebSocket push via {@code UserNotificationCreatedEvent}.
 *
 * <p>Reads access data through {@link AccessRequestLookupService} ({@code access.api}) so the
 * notifications module never reaches into {@code access.internal}. All failures are swallowed —
 * notification problems must never affect the access workflow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AccessNotificationListener {

    private final AccessRequestLookupService accessRequestLookupService;
    private final UserNotificationService userNotificationService;
    private final ObjectMapper objectMapper;

    @ApplicationModuleListener
    void onAccessRequestSubmitted(AccessRequestSubmittedEvent event) {
        dispatch(event.accessRequestId(), NotificationEventType.ACCESS_REQUEST_SUBMITTED,
                view -> accessRequestLookupService.findReviewerRecipients(event.accessRequestId()));
    }

    @ApplicationModuleListener
    void onAccessRequestApproved(AccessRequestApprovedEvent event) {
        dispatch(event.accessRequestId(), NotificationEventType.ACCESS_REQUEST_APPROVED,
                requesterRecipient());
    }

    @ApplicationModuleListener
    void onAccessRequestRejected(AccessRequestRejectedEvent event) {
        dispatch(event.accessRequestId(), NotificationEventType.ACCESS_REQUEST_REJECTED,
                requesterRecipient());
    }

    @ApplicationModuleListener
    void onAccessGrantExpired(AccessGrantExpiredEvent event) {
        dispatch(event.accessRequestId(), NotificationEventType.ACCESS_GRANT_EXPIRED,
                requesterRecipient());
    }

    @ApplicationModuleListener
    void onAccessGrantRevoked(AccessGrantRevokedEvent event) {
        dispatch(event.accessRequestId(), NotificationEventType.ACCESS_GRANT_REVOKED,
                requesterRecipient());
    }

    private static Function<AccessRequestView, Set<UUID>> requesterRecipient() {
        return view -> Set.of(view.requesterId());
    }

    private void dispatch(UUID accessRequestId, NotificationEventType type,
                          Function<AccessRequestView, Set<UUID>> recipientResolver) {
        try {
            var view = accessRequestLookupService.findById(accessRequestId).orElse(null);
            if (view == null) {
                log.debug("Skipping {} for unknown access request {}", type, accessRequestId);
                return;
            }
            var recipients = recipientResolver.apply(view);
            if (recipients == null || recipients.isEmpty()) {
                return;
            }
            userNotificationService.recordForUsers(type, recipients, view.organizationId(), null,
                    buildPayload(view));
        } catch (RuntimeException ex) {
            log.error("Access notification {} failed for request {}", type, accessRequestId, ex);
        }
    }

    private String buildPayload(AccessRequestView view) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("access_request_id", view.id().toString());
        if (view.datasourceName() != null) {
            payload.put("datasource", view.datasourceName());
        }
        if (view.requesterEmail() != null) {
            payload.put("requester", view.requesterEmail());
        }
        payload.put("requested_duration", view.requestedDuration());
        payload.put("status", view.status().name());
        if (view.expiresAt() != null) {
            payload.put("expires_at", view.expiresAt().toString());
        }
        return payload.toString();
    }
}
