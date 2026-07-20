package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableView;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiConnectorVariableControllerTest {

    private ApiConnectorVariableAdminService service;
    private AuditLogService auditLogService;
    private ApiConnectorVariableController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID variableId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        service = mock(ApiConnectorVariableAdminService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiConnectorVariableController(service, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal())
                .thenReturn(JwtClaims.forSystemRole(userId, "a@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private ApiConnectorVariableView view() {
        return new ApiConnectorVariableView(variableId, connectorId, "signature",
                ApiVariableKind.HMAC, "{{request.headers.Authorization}}{{request.body}}",
                ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX, true, "header:X-Signature",
                false, "Vendor signature", 0, Instant.now(), Instant.now());
    }

    private static CreateApiConnectorVariableRequest createRequest() {
        return new CreateApiConnectorVariableRequest("signature", ApiVariableKind.HMAC,
                "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX,
                "shared-key", "header:X-Signature", false, "Vendor signature", null);
    }

    @Test
    void listReturnsContent() {
        when(service.listForConnector(connectorId, orgId)).thenReturn(List.of(view()));

        var response = controller.list(connectorId, auth());

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().name()).isEqualTo("signature");
    }

    /** The secret is write-only: the response reports its presence and never its value. */
    @Test
    void responseCarriesOnlyTheSecretPresenceFlag() {
        when(service.listForConnector(connectorId, orgId)).thenReturn(List.of(view()));

        var body = controller.list(connectorId, auth()).content().getFirst();

        assertThat(body.hasSecret()).isTrue();
        assertThat(ApiConnectorVariableResponse.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase(java.util.Locale.ROOT).contains("secret")
                        && !"hasSecret".equals(c.getName()));
    }

    @Test
    void createDelegatesAndAudits() {
        when(service.create(eq(connectorId), eq(orgId), any())).thenReturn(view());

        var response = controller.create(connectorId, createRequest(), auth(), auditContext);

        assertThat(response.name()).isEqualTo("signature");
        verify(auditLogService).record(argThatHasAction(AuditAction.API_CONNECTOR_VARIABLE_CREATED));
    }

    /** Audit metadata must describe the shape of the change, never the expression or the secret. */
    @Test
    void auditMetadataOmitsTheExpressionAndSecret() {
        when(service.create(eq(connectorId), eq(orgId), any())).thenReturn(view());

        controller.create(connectorId, createRequest(), auth(), auditContext);

        var entry = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        var metadata = entry.getValue().metadata();
        assertThat(metadata).containsKeys("variable_id", "name", "kind", "has_secret", "overridable");
        assertThat(metadata).doesNotContainKeys("expression", "secret");
        assertThat(metadata.toString()).doesNotContain("request.headers.Authorization");
    }

    @Test
    void updateDelegatesAndAudits() {
        when(service.update(eq(variableId), eq(connectorId), eq(orgId), any())).thenReturn(view());
        var body = new UpdateApiConnectorVariableRequest("signature", ApiVariableKind.HMAC,
                "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX, null,
                null, "header:X-Signature", false, null, null);

        var response = controller.update(connectorId, variableId, body, auth(), auditContext);

        assertThat(response.id()).isEqualTo(variableId);
        verify(service).update(eq(variableId), eq(connectorId), eq(orgId), any());
        verify(auditLogService).record(argThatHasAction(AuditAction.API_CONNECTOR_VARIABLE_UPDATED));
    }

    @Test
    void deleteDelegatesAndAudits() {
        controller.delete(connectorId, variableId, auth(), auditContext);

        verify(service).delete(variableId, connectorId, orgId);
        verify(auditLogService).record(argThatHasAction(AuditAction.API_CONNECTOR_VARIABLE_DELETED));
    }

    @Test
    void reorderDelegatesAndAudits() {
        var order = List.of(variableId);
        when(service.reorder(eq(connectorId), eq(orgId), any())).thenReturn(List.of(view()));

        var response = controller.reorder(connectorId,
                new ReorderApiConnectorVariablesRequest(order), auth(), auditContext);

        assertThat(response.content()).hasSize(1);
        verify(auditLogService).record(argThatHasAction(AuditAction.API_CONNECTOR_VARIABLES_REORDERED));
    }

    @Test
    void requestsMapOntoCommandsFaithfully() {
        var createCommand = createRequest().toCommand();

        assertThat(createCommand.name()).isEqualTo("signature");
        assertThat(createCommand.kind()).isEqualTo(ApiVariableKind.HMAC);
        assertThat(createCommand.secret()).isEqualTo("shared-key");
        assertThat(createCommand.target()).isEqualTo("header:X-Signature");

        var updateCommand = new UpdateApiConnectorVariableRequest("v", ApiVariableKind.CONSTANT, "x",
                null, null, "s", true, null, true, "d", 3).toCommand();

        assertThat(updateCommand.clearSecret()).isTrue();
        assertThat(updateCommand.overridable()).isTrue();
        assertThat(updateCommand.sortOrder()).isEqualTo(3);

        assertThat(new ReorderApiConnectorVariablesRequest(List.of(variableId)).toCommand().variableIds())
                .containsExactly(variableId);
    }

    private static com.bablsoft.accessflow.audit.api.AuditEntry argThatHasAction(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(entry -> entry.action() == action);
    }
}
