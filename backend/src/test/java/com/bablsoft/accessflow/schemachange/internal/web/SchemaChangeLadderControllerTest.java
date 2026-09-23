package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderBlocker;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungState;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineEnvironmentView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaChangeLadderControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private SchemaChangeLadderService service;
    private SchemaChangeLadderController controller;

    @BeforeEach
    void setUp() {
        service = mock(SchemaChangeLadderService.class);
        controller = new SchemaChangeLadderController(service);
    }

    @Test
    void controllerIsGatedByTheSchemaChangeManagePermission() {
        var gate = SchemaChangeLadderController.class.getAnnotation(PreAuthorize.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')");
    }

    @Test
    void listPipelinesMapsEnvironments() {
        var datasourceId = UUID.randomUUID();
        when(service.listPipelines(orgId)).thenReturn(List.of(new SchemaChangePipelineView(pipelineId, "orders", true,
                List.of(new SchemaChangePipelineEnvironmentView(environmentId, "dev", 0, datasourceId)))));

        var response = controller.listPipelines(auth());

        assertThat(response).singleElement().extracting("id", "name", "active")
                .containsExactly(pipelineId, "orders", true);
        assertThat(response.getFirst().environments()).singleElement()
                .extracting("id", "name", "sortOrder", "datasourceId")
                .containsExactly(environmentId, "dev", 0, datasourceId);
    }

    @Test
    void ladderMapsRungsWithAndWithoutAPromotion() {
        var promotion = new SchemaChangePromotionView(UUID.randomUUID(), orgId, changeSetId, environmentId, "dev",
                UUID.randomUUID(), UUID.randomUUID(), SchemaChangePromotionStatus.APPLIED, "a".repeat(64), adminId,
                Instant.EPOCH, Instant.EPOCH, null, null, null);
        var applied = new SchemaChangeLadderRungView(environmentId, "dev", 0, UUID.randomUUID(), promotion,
                SchemaChangeLadderRungState.APPLIED, null, null, null, null, null, null);
        var frozen = new SchemaChangeLadderRungView(UUID.randomUUID(), "prod", 1, UUID.randomUUID(), null,
                SchemaChangeLadderRungState.BLOCKED, SchemaChangeLadderBlocker.FREEZE_ACTIVE, null, null,
                UUID.randomUUID(), FreezeBehavior.REJECT, "release freeze");
        when(service.ladder(orgId, changeSetId))
                .thenReturn(new SchemaChangeLadderView(changeSetId, pipelineId, List.of(applied, frozen)));

        var response = controller.ladder(changeSetId, auth());

        assertThat(response.pipelineId()).isEqualTo(pipelineId);
        assertThat(response.rungs().get(0).latestPromotion().status()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        assertThat(response.rungs().get(1).latestPromotion()).isNull();
        assertThat(response.rungs().get(1)).extracting("blocker", "freezeBehavior", "freezeReason")
                .containsExactly(SchemaChangeLadderBlocker.FREEZE_ACTIVE, FreezeBehavior.REJECT, "release freeze");
    }

    @Test
    void viewsCopyTheirListsAndTreatNullAsEmpty() {
        assertThat(new SchemaChangePipelineView(pipelineId, "p", true, null).environments()).isEmpty();
        assertThat(new SchemaChangeLadderView(changeSetId, pipelineId, null).rungs()).isEmpty();
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }
}
