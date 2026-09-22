package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.schemachange.api.PromoteSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
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

class SchemaChangePromotionControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID promotionId = UUID.randomUUID();

    private SchemaChangePromotionService service;
    private SchemaChangePromotionController controller;

    @BeforeEach
    void setUp() {
        service = mock(SchemaChangePromotionService.class);
        controller = new SchemaChangePromotionController(service);
    }

    @Test
    void controllerIsGatedByTheSchemaChangeManagePermission() {
        var gate = SchemaChangePromotionController.class.getAnnotation(PreAuthorize.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')");
    }

    @Test
    void promoteForwardsTheCallerEnvironmentAndRequestProvenance() {
        when(service.promote(eq(orgId), eq(adminId), eq(changeSetId), any())).thenReturn(view());

        var response = controller.promote(changeSetId, new PromoteSchemaChangeSetRequest(environmentId), auth(),
                new RequestAuditContext("10.0.0.7", "curl/8"));

        assertThat(response.id()).isEqualTo(promotionId);
        assertThat(response.status()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        var command = ArgumentCaptor.forClass(PromoteSchemaChangeSetCommand.class);
        verify(service).promote(eq(orgId), eq(adminId), eq(changeSetId), command.capture());
        assertThat(command.getValue()).extracting("environmentId", "submittedIp", "submittedUserAgent")
                .containsExactly(environmentId, "10.0.0.7", "curl/8");
    }

    @Test
    void listForChangeSetMapsEveryPromotion() {
        when(service.listForChangeSet(orgId, changeSetId)).thenReturn(List.of(view()));

        var response = controller.listForChangeSet(changeSetId, auth());

        assertThat(response).singleElement()
                .extracting("id", "environmentName").containsExactly(promotionId, "staging");
    }

    @Test
    void getMapsTheView() {
        when(service.get(orgId, promotionId)).thenReturn(view());

        assertThat(controller.get(promotionId, auth()).changeSetId()).isEqualTo(changeSetId);
    }

    @Test
    void cancelDelegatesWithTheCallingUser() {
        controller.cancel(promotionId, auth());

        verify(service).cancel(orgId, adminId, promotionId);
    }

    private SchemaChangePromotionView view() {
        return new SchemaChangePromotionView(promotionId, orgId, changeSetId, environmentId, "staging",
                UUID.randomUUID(), UUID.randomUUID(), SchemaChangePromotionStatus.PENDING, "a".repeat(64), adminId,
                Instant.parse("2026-09-22T10:00:00Z"), null, null, null, null);
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }
}
