package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiConnectorControllerTest {

    private ApiConnectorAdminService service;
    private AuditLogService auditLogService;
    private ApiConnectorController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        service = mock(ApiConnectorAdminService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiConnectorController(service, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth(UserRoleType role) {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new JwtClaims(userId, "a@acme.test", role, orgId));
        return authentication;
    }

    private ApiConnectorView view() {
        return new ApiConnectorView(connectorId, orgId, "Stripe", ApiProtocol.REST, "https://api.stripe.com",
                Map.of(), Map.of(), 5000, true, ApiAuthMethod.BEARER_TOKEN, true,
                null, null, null, null, null,
                com.bablsoft.accessflow.apigov.api.Oauth2GrantType.CLIENT_CREDENTIALS,
                com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth.CLIENT_SECRET_BASIC,
                false, false, false,
                null, true, null, false, false,
                true, 2048L, true, false, Instant.now());
    }

    @Test
    void listForAdminUsesAdminListing() {
        when(service.listForAdmin(eq(orgId), any())).thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var page = controller.list(auth(UserRoleType.ADMIN), Pageable.ofSize(20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).name()).isEqualTo("Stripe");
    }

    @Test
    void listForNonAdminUsesUserListing() {
        when(service.listForUser(eq(orgId), eq(userId), any()))
                .thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var page = controller.list(auth(UserRoleType.ANALYST), Pageable.ofSize(20));

        assertThat(page.content()).hasSize(1);
        verify(service).listForUser(eq(orgId), eq(userId), any());
    }

    @Test
    void getAdminVsUserRoutes() {
        when(service.getForAdmin(connectorId, orgId)).thenReturn(view());
        assertThat(controller.get(connectorId, auth(UserRoleType.ADMIN)).id()).isEqualTo(connectorId);

        when(service.getForUser(connectorId, orgId, userId)).thenReturn(view());
        assertThat(controller.get(connectorId, auth(UserRoleType.ANALYST)).id()).isEqualTo(connectorId);
    }

    @Test
    void createReturnsResponseAndAudits() {
        when(service.create(any())).thenReturn(view());
        var body = new CreateApiConnectorRequest("Stripe", ApiProtocol.REST, "https://api.stripe.com",
                Map.of(), null, 5000, true, ApiAuthMethod.BEARER_TOKEN, Map.of("token", "x"),
                null, null, null, null, null, null, null, null, null, null,
                null, true, null, false, false, true, 2048L);

        var response = controller.create(body, auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.name()).isEqualTo("Stripe");
        verify(auditLogService).record(auditEntry(AuditAction.API_CONNECTOR_CREATED));
    }

    @Test
    void updateAudits() {
        when(service.update(eq(connectorId), eq(orgId), any())).thenReturn(view());
        var body = new UpdateApiConnectorRequest("Stripe",
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        controller.update(connectorId, body, auth(UserRoleType.ADMIN), auditContext);

        verify(auditLogService).record(auditEntry(AuditAction.API_CONNECTOR_UPDATED));
    }

    @Test
    void deleteAudits() {
        controller.delete(connectorId, auth(UserRoleType.ADMIN), auditContext);

        verify(service).delete(connectorId, orgId);
        verify(auditLogService).record(auditEntry(AuditAction.API_CONNECTOR_DELETED));
    }

    @Test
    void testProbeMapsResult() {
        when(service.test(connectorId, orgId)).thenReturn(new ApiConnectionTestResult(true, "HTTP 200"));

        var response = controller.test(connectorId, auth(UserRoleType.ADMIN));

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("HTTP 200");
    }

    @Test
    void listPermissionsMapsViews() {
        when(service.listPermissions(connectorId, orgId)).thenReturn(List.of(permissionView()));

        var list = controller.listPermissions(connectorId, auth(UserRoleType.ADMIN));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).canRead()).isTrue();
    }

    @Test
    void grantAndRevokeAudit() {
        var target = UUID.randomUUID();
        when(service.grantPermission(eq(connectorId), eq(orgId), eq(userId), any()))
                .thenReturn(permissionView());
        controller.grant(connectorId, new GrantApiConnectorPermissionRequest(target, true, false, false,
                null, null, null), auth(UserRoleType.ADMIN), auditContext);
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_GRANTED));

        var permId = UUID.randomUUID();
        controller.revoke(connectorId, permId, auth(UserRoleType.ADMIN), auditContext);
        verify(service).revokePermission(connectorId, orgId, permId);
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_REVOKED));
    }

    @Test
    void updatePermissionDelegatesAndAudits() {
        var permId = UUID.randomUUID();
        when(service.updatePermission(eq(connectorId), eq(orgId), eq(permId), any()))
                .thenReturn(permissionView());

        var response = controller.update(connectorId, permId,
                new UpdateApiConnectorPermissionRequest(false, true, false, null,
                        List.of("createPet"), List.of("data.token")),
                auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.canRead()).isTrue();
        verify(service).updatePermission(eq(connectorId), eq(orgId), eq(permId), any());
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_UPDATED));
    }

    @Test
    void listGroupPermissionsMapsViews() {
        when(service.listGroupPermissions(connectorId, orgId)).thenReturn(List.of(groupPermissionView()));

        var list = controller.listGroupPermissions(connectorId, auth(UserRoleType.ADMIN));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).groupName()).isEqualTo("Analysts");
        assertThat(list.get(0).memberCount()).isEqualTo(3);
    }

    @Test
    void grantAndRevokeGroupAudit() {
        var groupId = UUID.randomUUID();
        when(service.grantGroupPermission(eq(connectorId), eq(orgId), eq(userId), any()))
                .thenReturn(groupPermissionView());
        controller.grantGroup(connectorId,
                new GrantApiConnectorGroupPermissionRequest(groupId, true, false, false, null, null, null),
                auth(UserRoleType.ADMIN), auditContext);
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_GROUP_GRANTED));

        var permId = UUID.randomUUID();
        controller.revokeGroup(connectorId, permId, auth(UserRoleType.ADMIN), auditContext);
        verify(service).revokeGroupPermission(connectorId, orgId, permId);
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_GROUP_REVOKED));
    }

    @Test
    void updateGroupPermissionDelegatesAndAudits() {
        var permId = UUID.randomUUID();
        when(service.updateGroupPermission(eq(connectorId), eq(orgId), eq(permId), any()))
                .thenReturn(groupPermissionView());

        var response = controller.updateGroup(connectorId, permId,
                new UpdateApiConnectorGroupPermissionRequest(true, false, false, null,
                        List.of("listPets"), List.of("data.ssn")),
                auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.groupName()).isEqualTo("Analysts");
        verify(service).updateGroupPermission(eq(connectorId), eq(orgId), eq(permId), any());
        verify(auditLogService).record(auditEntry(AuditAction.API_PERMISSION_GROUP_UPDATED));
    }

    private ApiConnectorPermissionView permissionView() {
        return new ApiConnectorPermissionView(UUID.randomUUID(), connectorId, UUID.randomUUID(),
                "u@acme.test", "User", true, false, false, null, List.of(), List.of(), Instant.now());
    }

    private com.bablsoft.accessflow.apigov.api.ApiConnectorGroupPermissionView groupPermissionView() {
        return new com.bablsoft.accessflow.apigov.api.ApiConnectorGroupPermissionView(UUID.randomUUID(),
                connectorId, UUID.randomUUID(), "Analysts", 3, true, false, false, null,
                List.of(), List.of(), Instant.now());
    }

    private static AuditEntry auditEntry(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.action() == action);
    }
}
