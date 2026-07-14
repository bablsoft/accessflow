package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestLookupService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.events.AccessGrantExpiredEvent;
import com.bablsoft.accessflow.access.events.AccessGrantRevokedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestApprovedEvent;
import com.bablsoft.accessflow.access.events.AccessRequestRejectedEvent;
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

import static org.assertj.core.api.Assertions.assertThatCode;
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
                AccessResourceKind.DATASOURCE, UUID.randomUUID(), "db", null, null, true, false,
                false, List.of("public"), null, null, "PT4H", "need access", false, status,
                Instant.now().plusSeconds(3600), null, Instant.now(), Instant.now());
    }

    private AccessRequestView connectorView(AccessGrantStatus status) {
        return new AccessRequestView(requestId, organizationId, requesterId, "u@x.io",
                AccessResourceKind.API_CONNECTOR, null, null, UUID.randomUUID(), "billing-api",
                true, true, false, null, null, List.of("listCharges"), "PT4H", "need access",
                false, status, Instant.now().plusSeconds(3600), null, Instant.now(), Instant.now());
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
                eq(organizationId), eq(null), eq(null), any());
    }

    @Test
    void approvedNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.APPROVED)));

        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_REQUEST_APPROVED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), eq(null), any());
    }

    @Test
    void rejectedNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.REJECTED)));

        listener().onAccessRequestRejected(new AccessRequestRejectedEvent(requestId, UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_REQUEST_REJECTED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), eq(null), any());
    }

    @Test
    void revokedNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.REVOKED)));

        listener().onAccessGrantRevoked(
                new AccessGrantRevokedEvent(requestId, requesterId, UUID.randomUUID(), UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_GRANT_REVOKED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), eq(null), any());
    }

    @Test
    void swallowsLookupFailure() {
        when(accessRequestLookupService.findById(requestId)).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> listener().onAccessRequestApproved(
                new AccessRequestApprovedEvent(requestId, UUID.randomUUID())))
                .doesNotThrowAnyException();
        verify(userNotificationService, never())
                .recordForUsers(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredNotifiesRequester() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.EXPIRED)));

        listener().onAccessGrantExpired(new AccessGrantExpiredEvent(requestId, requesterId, UUID.randomUUID()));

        verify(userNotificationService).recordForUsers(
                eq(NotificationEventType.ACCESS_GRANT_EXPIRED), eq(Set.of(requesterId)),
                eq(organizationId), eq(null), eq(null), any());
    }

    @Test
    void unknownRequestIsSkipped() {
        when(accessRequestLookupService.findById(requestId)).thenReturn(Optional.empty());
        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));
        verify(userNotificationService, never())
                .recordForUsers(any(), any(), any(), any(), any(), any());
    }

    @Test
    void payloadCarriesResourceKindAndDatasourceName() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.APPROVED)));

        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));

        var payload = capturePayload();
        org.assertj.core.api.Assertions.assertThat(payload.get("resource_kind").asText())
                .isEqualTo("DATASOURCE");
        org.assertj.core.api.Assertions.assertThat(payload.get("datasource").asText())
                .isEqualTo("db");
        org.assertj.core.api.Assertions.assertThat(payload.has("connector")).isFalse();
    }

    @Test
    void payloadCarriesConnectorNameForConnectorRequest() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(connectorView(AccessGrantStatus.APPROVED)));

        listener().onAccessRequestApproved(new AccessRequestApprovedEvent(requestId, UUID.randomUUID()));

        var payload = capturePayload();
        org.assertj.core.api.Assertions.assertThat(payload.get("resource_kind").asText())
                .isEqualTo("API_CONNECTOR");
        org.assertj.core.api.Assertions.assertThat(payload.get("connector").asText())
                .isEqualTo("billing-api");
        org.assertj.core.api.Assertions.assertThat(payload.has("datasource")).isFalse();
    }

    private tools.jackson.databind.JsonNode capturePayload() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(userNotificationService).recordForUsers(any(), any(), any(), any(), any(),
                captor.capture());
        return new ObjectMapper().readTree(captor.getValue());
    }

    @Test
    void emptyRecipientsSkipsRecording() {
        when(accessRequestLookupService.findById(requestId))
                .thenReturn(Optional.of(view(AccessGrantStatus.PENDING)));
        when(accessRequestLookupService.findReviewerRecipients(requestId)).thenReturn(Set.of());

        listener().onAccessRequestSubmitted(new AccessRequestSubmittedEvent(requestId, requesterId));

        verify(userNotificationService, never())
                .recordForUsers(any(), any(), any(), any(), any(), any());
    }
}
