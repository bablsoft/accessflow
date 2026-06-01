package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestLookupService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.events.AccessGrantExpiredEvent;
import com.bablsoft.accessflow.access.events.AccessRequestApprovedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestSubmittedEvent;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessNotificationListenerTest {

    @Mock AccessRequestLookupService accessRequestLookupService;
    @Mock UserNotificationService userNotificationService;

    private AccessNotificationListener listener() {
        return new AccessNotificationListener(accessRequestLookupService, userNotificationService,
                new ObjectMapper());
    }

    private final UUID requestId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private AccessRequestView view(AccessGrantStatus status) {
        return new AccessRequestView(requestId, organizationId, requesterId, "u@x.io",
                UUID.randomUUID(), "db", true, false, false, List.of("public"), null, "PT4H",
                "need access", status, Instant.now().plusSeconds(3600), null, Instant.now(),
                Instant.now());
    }

    @Test
    void submittedNotifiesReviewers() {
        var reviewer = UUID.randomUUID();
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.PENDING)));
        when(accessRequestLookupService.findReviewerRecipients(requestId))
                .thenReturn(Set.of(reviewer));

        listener().onAccessRequestSubmitted(new AccessRequestSubmittedEvent(requestId, requesterId));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_REQUEST_SUBMITTED), eq(Set.of(reviewer)),
                eq(organizationId), eq(null), any());
    }

    @Test
    void approvedNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.APPROVED)));

        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_REQUEST_APPROVED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), any());
    }

    @Test
    void expiredNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.EXPIRED)));

        listener().onAccessGrantExpired(new AccessGrantExpiredEvent(requestId, requesterId, UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_GRANT_EXPIRED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), any());
    }

    @Test
    void unknownRequestIsSkipped() {
        when(accessRequestLookupService.findById(requestId)).thenReturn(Optional.empty());
        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));
        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any());
    }

    @Test
    void emptyRecipientsSkipsRecording() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.PENDING)));
        when(accessRequestLookupService.findReviewerRecipients(requestId)).thenReturn(Set.of());

        listener().onAccessRequestSubmitted(new AccessRequestSubmittedEvent(requestId, requesterId));

        verify(userNotificationService, never()).recordForUsers(any(), any(), any(), any(), any());
    }
}
