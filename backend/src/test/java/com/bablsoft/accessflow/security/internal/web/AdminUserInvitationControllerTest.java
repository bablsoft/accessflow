package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.InviteUserCommand;
import com.bablsoft.accessflow.security.api.IssuedInvitation;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.UserInvitationService;
import com.bablsoft.accessflow.security.api.UserInvitationStatusType;
import com.bablsoft.accessflow.security.api.UserInvitationView;
import com.bablsoft.accessflow.security.internal.web.model.InviteUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserInvitationControllerTest {

    @Mock UserInvitationService invitationService;
    @Mock AuditLogService auditLogService;

    private AdminUserInvitationController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua/1");

    @BeforeEach
    void setUp() {
        controller = new AdminUserInvitationController(invitationService, auditLogService);
    }

    private UsernamePasswordAuthenticationToken auth() {
        var claims = new JwtClaims(userId, "admin@example.com", UserRoleType.ADMIN, orgId);
        return new UsernamePasswordAuthenticationToken(claims, "n/a", List.of());
    }

    @Test
    void inviteReturns201AndAudits() {
        var view = sampleView();
        when(invitationService.invite(any(InviteUserCommand.class), eq(orgId), eq(userId)))
                .thenReturn(new IssuedInvitation(view, "raw-token"));

        var resp = controller.invite(new InviteUserRequest("alice@example.com", "Alice",
                UserRoleType.ANALYST), auth(), auditContext);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().email()).isEqualTo("alice@example.com");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void resendReturnsViewAndAudits() {
        var view = sampleView();
        when(invitationService.resend(eq(view.id()), eq(orgId), eq(userId)))
                .thenReturn(new IssuedInvitation(view, "new-token"));

        var resp = controller.resend(view.id(), auth(), auditContext);

        assertThat(resp.email()).isEqualTo("alice@example.com");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void revokeReturns204AndAudits() {
        var id = UUID.randomUUID();

        var resp = controller.revoke(id, auth(), auditContext);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(invitationService).revoke(id, orgId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void listMapsPageResponse() {
        var view = sampleView();
        var page = new PageResponse<>(List.of(view), 0, 20, 1L, 1);
        when(invitationService.list(eq(orgId), any(PageRequest.class))).thenReturn(page);

        var resp = controller.list(auth(), org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(resp.content()).hasSize(1);
        assertThat(resp.totalElements()).isEqualTo(1);
    }

    private UserInvitationView sampleView() {
        return new UserInvitationView(UUID.randomUUID(), orgId, "alice@example.com", "Alice",
                UserRoleType.ANALYST, UserInvitationStatusType.PENDING,
                Instant.now().plusSeconds(3600), null, null, userId, Instant.now());
    }
}
