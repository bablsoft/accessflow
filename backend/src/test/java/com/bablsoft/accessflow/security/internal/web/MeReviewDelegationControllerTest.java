package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.CreateReviewDelegationCommand;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.ReviewDelegationService;
import com.bablsoft.accessflow.core.api.ReviewDelegationStatus;
import com.bablsoft.accessflow.core.api.ReviewDelegationView;
import com.bablsoft.accessflow.security.internal.web.model.CreateReviewDelegationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeReviewDelegationControllerTest {

    private ReviewDelegationService service;
    private AuditLogService auditLogService;
    private MeReviewDelegationController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID delegateId = UUID.randomUUID();
    private final UUID delegationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ReviewDelegationService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new MeReviewDelegationController(service, auditLogService);
    }

    private ReviewDelegationView view(DelegationScopeKind kind, UUID scopeId) {
        return new ReviewDelegationView(delegationId, orgId, userId, "Alice", "alice@example.com",
                delegateId, "Bob", "bob@example.com", kind, scopeId, "Prod PG", "Annual leave",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-30T00:00:00Z"),
                null, ReviewDelegationStatus.SCHEDULED, Instant.parse("2026-08-16T09:00:00Z"));
    }

    private CreateReviewDelegationRequest request(DelegationScopeKind kind, UUID scopeId) {
        return new CreateReviewDelegationRequest(delegateId, kind, scopeId, "Annual leave",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-30T00:00:00Z"));
    }

    @Test
    void listReturnsBothDirections() {
        when(service.listGrantedBy(orgId, userId)).thenReturn(List.of(view(null, null)));
        when(service.listReceivedBy(orgId, userId)).thenReturn(List.of());

        var body = controller.list(userId, orgId);

        assertThat(body.granted()).hasSize(1);
        assertThat(body.received()).isEmpty();
        assertThat(body.granted().get(0).delegate().email()).isEqualTo("bob@example.com");
    }

    @Test
    void createDelegatesTheCallerAsDelegatorAndReturns201WithLocation() {
        when(service.create(any())).thenReturn(view(null, null));

        var response = controller.create(request(null, null), userId, orgId);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/me/review-delegations/" + delegationId);
        var captor = ArgumentCaptor.forClass(CreateReviewDelegationCommand.class);
        verify(service).create(captor.capture());
        // The caller is always the delegator — you may not create a delegation for someone else.
        assertThat(captor.getValue().delegatorUserId()).isEqualTo(userId);
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
    }

    @Test
    void createAuditsWithTheDelegateAndScope() {
        var scopeId = UUID.randomUUID();
        when(service.create(any())).thenReturn(view(DelegationScopeKind.DATASOURCE, scopeId));

        controller.create(request(DelegationScopeKind.DATASOURCE, scopeId), userId, orgId);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.REVIEW_DELEGATION_CREATED);
        assertThat(captor.getValue().metadata())
                .containsEntry("delegate_user_id", delegateId.toString())
                .containsEntry("scope_kind", "DATASOURCE")
                .containsEntry("scope_id", scopeId.toString());
    }

    @Test
    void anUnscopedDelegationAuditsWithoutScopeKeys() {
        when(service.create(any())).thenReturn(view(null, null));

        controller.create(request(null, null), userId, orgId);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().metadata()).doesNotContainKey("scope_kind");
    }

    @Test
    void revokeReturns204AndAudits() {
        var response = controller.revoke(delegationId, userId, orgId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).revoke(delegationId, orgId, userId);
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.REVIEW_DELEGATION_REVOKED);
    }
}
