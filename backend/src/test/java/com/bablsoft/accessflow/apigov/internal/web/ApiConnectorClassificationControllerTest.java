package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationDerivationView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagView;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DataClassification;
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

class ApiConnectorClassificationControllerTest {

    private ApiConnectorClassificationAdminService service;
    private AuditLogService auditLogService;
    private ApiConnectorClassificationController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID tagId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        service = mock(ApiConnectorClassificationAdminService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiConnectorClassificationController(service, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal())
                .thenReturn(JwtClaims.forSystemRole(userId, "a@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private ApiConnectorClassificationTagView tag() {
        return new ApiConnectorClassificationTagView(tagId, connectorId, null, "user.ssn",
                ApiMaskingMatcherType.JSON_PATH, DataClassification.PII, "note", Instant.now(), Instant.now());
    }

    @Test
    void listReturnsContent() {
        when(service.listForConnector(connectorId, orgId)).thenReturn(List.of(tag()));

        var response = controller.list(connectorId, auth());

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().classification()).isEqualTo(DataClassification.PII);
    }

    @Test
    void createDelegatesAndAudits() {
        when(service.create(eq(connectorId), eq(orgId), any())).thenReturn(List.of(tag()));
        var body = new CreateApiClassificationTagRequest(ApiMaskingMatcherType.JSON_PATH, null, "user.ssn",
                List.of(DataClassification.PII), "note", true);

        var response = controller.create(connectorId, body, auth(), auditContext);

        assertThat(response.content()).hasSize(1);
        var action = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(action.capture());
        assertThat(action.getValue().action())
                .isEqualTo(AuditAction.API_CONNECTOR_CLASSIFICATION_TAG_ADDED);
    }

    @Test
    void deleteDelegatesAndAudits() {
        controller.delete(connectorId, tagId, auth(), auditContext);

        verify(service).delete(tagId, connectorId, orgId);
        var action = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(action.capture());
        assertThat(action.getValue().action())
                .isEqualTo(AuditAction.API_CONNECTOR_CLASSIFICATION_TAG_REMOVED);
    }

    @Test
    void derivationPreviewDelegates() {
        when(service.previewDerivation(connectorId, orgId)).thenReturn(
                new ApiConnectorClassificationDerivationView(
                        new ApiConnectorClassificationDerivationView.ReviewPosture(true, true, 2,
                                List.of(DataClassification.PCI)),
                        List.of()));

        var response = controller.derivationPreview(connectorId, auth());

        assertThat(response.suggestedReviewPosture().minApprovals()).isEqualTo(2);
        assertThat(response.suggestedReviewPosture().drivenBy()).contains(DataClassification.PCI);
    }
}
