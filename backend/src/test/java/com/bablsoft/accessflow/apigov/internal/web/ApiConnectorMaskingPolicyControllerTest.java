package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyView;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class ApiConnectorMaskingPolicyControllerTest {

    private ApiConnectorMaskingAdminService service;
    private AuditLogService auditLogService;
    private ApiConnectorMaskingPolicyController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        service = mock(ApiConnectorMaskingAdminService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiConnectorMaskingPolicyController(service, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal())
                .thenReturn(JwtClaims.forSystemRole(userId, "a@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private ApiConnectorMaskingPolicyView view() {
        return new ApiConnectorMaskingPolicyView(policyId, connectorId, ApiMaskingMatcherType.JSON_PATH,
                null, "user.ssn", MaskingStrategy.FULL, Map.of(), List.of("ADMIN"), List.of(), List.of(),
                true, Instant.now(), Instant.now());
    }

    @Test
    void listReturnsContent() {
        when(service.listForConnector(connectorId, orgId)).thenReturn(List.of(view()));

        var response = controller.list(connectorId, auth());

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().fieldRef()).isEqualTo("user.ssn");
    }

    @Test
    void createDelegatesAndAudits() {
        when(service.create(eq(connectorId), eq(orgId), any())).thenReturn(view());
        var body = new CreateApiMaskingPolicyRequest(ApiMaskingMatcherType.JSON_PATH, null, "user.ssn",
                MaskingStrategy.FULL, Map.of(), List.of("ADMIN"), List.of(), List.of(), true);

        var response = controller.create(connectorId, body, auth(), auditContext);

        assertThat(response.id()).isEqualTo(policyId);
        var action = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(action.capture());
        assertThat(action.getValue().action()).isEqualTo(AuditAction.API_CONNECTOR_MASKING_POLICY_CREATED);
    }

    @Test
    void updateDelegatesAndAudits() {
        when(service.update(eq(policyId), eq(connectorId), eq(orgId), any())).thenReturn(view());
        var body = new UpdateApiMaskingPolicyRequest(ApiMaskingMatcherType.JSON_PATH, null, "user.ssn",
                MaskingStrategy.FULL, Map.of(), List.of(), List.of(), List.of(), true);

        controller.update(connectorId, policyId, body, auth(), auditContext);

        verify(service).update(eq(policyId), eq(connectorId), eq(orgId), any());
    }

    @Test
    void deleteDelegatesAndAudits() {
        controller.delete(connectorId, policyId, auth(), auditContext);

        verify(service).delete(policyId, connectorId, orgId);
        var action = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(action.capture());
        assertThat(action.getValue().action()).isEqualTo(AuditAction.API_CONNECTOR_MASKING_POLICY_DELETED);
    }
}
