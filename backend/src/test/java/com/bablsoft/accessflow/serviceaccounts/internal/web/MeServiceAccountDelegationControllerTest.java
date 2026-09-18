package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.GrantServiceAccountDelegationCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationStatus;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeServiceAccountDelegationControllerTest {

    private final ServiceAccountDelegationService delegationService = mock(ServiceAccountDelegationService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final MeServiceAccountDelegationController controller =
            new MeServiceAccountDelegationController(delegationService, auditLogService);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(alice, "alice@x.com", UserRoleType.ANALYST, organizationId), "n/a", List.of());

    private ServiceAccountDelegationView view(Instant expiresAt, Instant revokedAt) {
        return new ServiceAccountDelegationView(UUID.randomUUID(), organizationId, agent, "bot@x.com", alice,
                "alice@x.com", alice, Instant.EPOCH, expiresAt, revokedAt,
                revokedAt == null ? ServiceAccountDelegationStatus.ACTIVE : ServiceAccountDelegationStatus.REVOKED);
    }

    @Test
    void anySignedInUserMayReachTheSelfServiceSurface() {
        assertThat(MeServiceAccountDelegationController.class.getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    void listReturnsTheCallersOwnGrants() {
        when(delegationService.listForPrincipal(organizationId, alice)).thenReturn(List.of(view(null, null)));

        var result = controller.list(authentication);

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.serviceAccountUserId()).isEqualTo(agent);
            assertThat(r.principalUserId()).isEqualTo(alice);
        });
    }

    @Test
    void grantNamesTheCallerAsPrincipalAndAudits() {
        var expiry = Instant.EPOCH.plusSeconds(60);
        var view = view(expiry, null);
        when(delegationService.grant(eq(organizationId), eq(alice), any())).thenReturn(view);

        var response = controller.grant(new GrantMyServiceAccountDelegationRequest(agent, expiry),
                authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(view.id());
        var command = ArgumentCaptor.forClass(GrantServiceAccountDelegationCommand.class);
        verify(delegationService).grant(eq(organizationId), eq(alice), command.capture());
        assertThat(command.getValue().serviceAccountUserId()).isEqualTo(agent);
        assertThat(command.getValue().principalUserId()).isEqualTo(alice);
        assertThat(command.getValue().expiresAt()).isEqualTo(expiry);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_DELEGATION_GRANTED);
        assertThat(audit.resourceType()).isEqualTo(AuditResourceType.SERVICE_ACCOUNT);
        assertThat(audit.resourceId()).isEqualTo(agent);
        assertThat(audit.actorId()).isEqualTo(alice);
        assertThat(audit.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(audit.metadata()).containsEntry("delegation_id", view.id().toString())
                .containsEntry("principal_user_id", alice.toString())
                .containsEntry("channel", "self_service")
                .containsEntry("expires_at", expiry.toString());
    }

    @Test
    void revokeIsScopedToTheCallerAndAudits() {
        var view = view(null, Instant.EPOCH.plusSeconds(5));
        when(delegationService.revoke(organizationId, alice, view.id(), null, alice)).thenReturn(view);

        var response = controller.revoke(view.id(), authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_DELEGATION_REVOKED);
        assertThat(audit.metadata()).doesNotContainKey("expires_at");
    }

    @Test
    void anAuditFailureNeverFailsTheRequest() {
        when(delegationService.grant(eq(organizationId), eq(alice), any())).thenReturn(view(null, null));
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        var response = controller.grant(new GrantMyServiceAccountDelegationRequest(agent, null),
                authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private AuditEntry recordedAudit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }
}
