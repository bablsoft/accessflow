package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateRoleCommand;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleAdminService;
import com.bablsoft.accessflow.core.api.RoleView;
import com.bablsoft.accessflow.core.api.SystemRoleImmutableException;
import com.bablsoft.accessflow.core.api.UpdateRoleCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateRoleRequest;
import com.bablsoft.accessflow.security.internal.web.model.UpdateRoleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleControllerTest {

    @Mock RoleAdminService roleAdminService;
    @Mock AuditLogService auditLogService;

    private RoleController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua/1");

    @BeforeEach
    void setUp() {
        controller = new RoleController(roleAdminService, auditLogService);
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/roles");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Authentication auth() {
        var claims = JwtClaims.forSystemRole(userId, "admin@acme.io", UserRoleType.ADMIN, orgId);
        return new UsernamePasswordAuthenticationToken(claims, null);
    }

    private RoleView view(String name, boolean system, Set<Permission> permissions) {
        return new RoleView(roleId, system ? null : orgId, name, "desc", system, permissions,
                0, Instant.now(), Instant.now());
    }

    @Test
    void listReturnsRoles() {
        when(roleAdminService.list(orgId)).thenReturn(List.of(
                view("ADMIN", true, Set.of(Permission.ROLE_MANAGE)),
                view("Data Steward", false, Set.of(Permission.QUERY_REVIEW))));

        var response = controller.list(auth());

        assertThat(response.roles()).hasSize(2);
        assertThat(response.roles().get(0).system()).isTrue();
        assertThat(response.roles().get(1).permissions()).containsExactly("QUERY_REVIEW");
    }

    @Test
    void getReturnsRole() {
        when(roleAdminService.get(roleId, orgId))
                .thenReturn(view("Data Steward", false, Set.of()));

        var response = controller.get(roleId, auth());

        assertThat(response.name()).isEqualTo("Data Steward");
    }

    @Test
    void createReturns201WithLocationAndAudits() {
        when(roleAdminService.create(any(CreateRoleCommand.class)))
                .thenReturn(view("Data Steward", false, Set.of(Permission.QUERY_REVIEW)));

        var response = controller.create(
                new CreateRoleRequest("Data Steward", "desc", Set.of(Permission.QUERY_REVIEW)),
                auth(), auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.ROLE_CREATED);
        assertThat(captor.getValue().resourceType()).isEqualTo(AuditResourceType.ROLE);
    }

    @Test
    void updateDelegatesAndAudits() {
        when(roleAdminService.update(eq(roleId), eq(orgId), any(UpdateRoleCommand.class)))
                .thenReturn(view("Renamed", false, Set.of()));

        var response = controller.update(roleId,
                new UpdateRoleRequest("Renamed", null, Set.of()), auth(), auditContext);

        assertThat(response.name()).isEqualTo("Renamed");
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.ROLE_UPDATED);
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(roleId, auth(), auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(roleAdminService).delete(roleId, orgId);
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.ROLE_DELETED);
    }

    @Test
    void systemRoleImmutablePropagates() {
        when(roleAdminService.update(eq(roleId), eq(orgId), any(UpdateRoleCommand.class)))
                .thenThrow(new SystemRoleImmutableException(roleId));

        assertThatThrownBy(() -> controller.update(roleId,
                new UpdateRoleRequest("x", null, null), auth(), auditContext))
                .isInstanceOf(SystemRoleImmutableException.class);
    }
}
