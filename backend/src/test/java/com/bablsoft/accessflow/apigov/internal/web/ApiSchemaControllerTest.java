package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiSchemaControllerTest {

    private ApiSchemaService schemaService;
    private ApiConnectorAdminService connectorService;
    private AuditLogService auditLogService;
    private ApiSchemaController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        schemaService = mock(ApiSchemaService.class);
        connectorService = mock(ApiConnectorAdminService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiSchemaController(schemaService, connectorService, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth(UserRoleType role) {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new JwtClaims(userId, "a@acme.test", role, orgId));
        return authentication;
    }

    private ApiSchemaView schemaView() {
        return new ApiSchemaView(UUID.randomUUID(), connectorId, ApiSchemaType.OPENAPI, null, 3, Instant.now());
    }

    @Test
    void uploadReturnsResponseAndAudits() {
        when(schemaService.upload(eq(connectorId), eq(orgId), eq(ApiSchemaType.OPENAPI), eq("spec"), any()))
                .thenReturn(schemaView());

        var response = controller.upload(connectorId,
                new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, "spec", null), auth(UserRoleType.ADMIN),
                auditContext);

        assertThat(response.operationCount()).isEqualTo(3);
        verify(auditLogService).record(auditEntry(AuditAction.API_SCHEMA_UPLOADED));
    }

    @Test
    void listMapsViews() {
        when(schemaService.list(connectorId, orgId)).thenReturn(List.of(schemaView()));

        assertThat(controller.list(connectorId, auth(UserRoleType.ADMIN))).hasSize(1);
    }

    @Test
    void deleteAudits() {
        controller.delete(connectorId, UUID.randomUUID(), auth(UserRoleType.ADMIN), auditContext);

        verify(auditLogService).record(auditEntry(AuditAction.API_SCHEMA_DELETED));
    }

    @Test
    void operationsForAdminSkipsPermissionCheck() {
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null)));

        var ops = controller.operations(connectorId, auth(UserRoleType.ADMIN));

        assertThat(ops).hasSize(1);
        verifyNoInteractions(connectorService);
    }

    @Test
    void operationsForNonAdminEnforcesPermission() {
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of());

        controller.operations(connectorId, auth(UserRoleType.ANALYST));

        verify(connectorService).getForUser(connectorId, orgId, userId);
    }

    private static AuditEntry auditEntry(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.action() == action);
    }
}
