package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigService;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftService;
import com.bablsoft.accessflow.schemachange.api.UpsertSchemaDriftConfigCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
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

class SchemaDriftControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID scanId = UUID.randomUUID();
    private final UUID findingId = UUID.randomUUID();

    private SchemaDriftService driftService;
    private SchemaDriftConfigService configService;
    private SchemaDriftController controller;

    @BeforeEach
    void setUp() {
        driftService = mock(SchemaDriftService.class);
        configService = mock(SchemaDriftConfigService.class);
        controller = new SchemaDriftController(driftService, configService);
    }

    @Test
    void controllerIsGatedByTheSchemaChangeManagePermission() {
        var gate = SchemaDriftController.class.getAnnotation(PreAuthorize.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')");
    }

    @Test
    void listScansForwardsBothFiltersAndTheCallersOrganization() {
        when(driftService.listScans(eq(orgId), any(), any()))
                .thenReturn(new PageResponse<>(List.of(scanView()), 0, 20, 1, 1));

        var response = controller.listScans(pipelineId, environmentId, PageRequest.of(0, 20), auth());

        assertThat(response.content()).singleElement()
                .satisfies(scan -> assertThat(scan.id()).isEqualTo(scanId));
        var filter = ArgumentCaptor.forClass(SchemaDriftScanListFilter.class);
        verify(driftService).listScans(eq(orgId), filter.capture(), any());
        assertThat(filter.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(filter.getValue().environmentId()).isEqualTo(environmentId);
    }

    @Test
    void listFindingsForwardsEveryFilter() {
        when(driftService.listFindings(eq(orgId), any(), any()))
                .thenReturn(new PageResponse<>(List.of(findingView()), 0, 20, 1, 1));

        var response = controller.listFindings(pipelineId, environmentId, SchemaDriftFindingStatus.OPEN,
                PageRequest.of(0, 20), auth());

        assertThat(response.totalElements()).isEqualTo(1);
        var filter = ArgumentCaptor.forClass(SchemaDriftFindingListFilter.class);
        verify(driftService).listFindings(eq(orgId), filter.capture(), any());
        assertThat(filter.getValue().status()).isEqualTo(SchemaDriftFindingStatus.OPEN);
        assertThat(filter.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(filter.getValue().environmentId()).isEqualTo(environmentId);
    }

    @Test
    void scanNowReturnsTheReceiptToPoll() {
        when(driftService.scanNow(orgId, adminId, environmentId)).thenReturn(scanView());

        var response = controller.scanNow(new RequestSchemaDriftScanRequest(environmentId), auth());

        assertThat(response.id()).isEqualTo(scanId);
        assertThat(response.finishedAt()).isNull();
    }

    @Test
    void acknowledgeDelegatesWithTheCallingUser() {
        when(driftService.acknowledge(orgId, adminId, findingId)).thenReturn(findingView());

        assertThat(controller.acknowledge(findingId, auth()).id()).isEqualTo(findingId);
        verify(driftService).acknowledge(orgId, adminId, findingId);
    }

    @Test
    void listConfigsMapsEveryRow() {
        when(configService.list(orgId)).thenReturn(List.of(configView()));

        assertThat(controller.listConfigs(auth())).singleElement()
                .satisfies(config -> assertThat(config.pipelineId()).isEqualTo(pipelineId));
    }

    @Test
    void getConfigDelegates() {
        when(configService.get(orgId, pipelineId)).thenReturn(configView());

        assertThat(controller.getConfig(pipelineId, auth()).enabled()).isTrue();
    }

    @Test
    void upsertConfigForwardsTheWholeCommand() {
        when(configService.upsert(eq(orgId), eq(adminId), eq(pipelineId), any())).thenReturn(configView());

        controller.upsertConfig(pipelineId, new UpsertSchemaDriftConfigRequest(true,
                SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId, 8), auth());

        var command = ArgumentCaptor.forClass(UpsertSchemaDriftConfigCommand.class);
        verify(configService).upsert(eq(orgId), eq(adminId), eq(pipelineId), command.capture());
        assertThat(command.getValue()).extracting("enabled", "baseline", "baselineEnvironmentId",
                        "scanIntervalHours")
                .containsExactly(true, SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId, 8);
    }

    private SchemaDriftScanView scanView() {
        return new SchemaDriftScanView(scanId, orgId, pipelineId, environmentId, UUID.randomUUID(),
                SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, NOW, null, true, 0, false, null);
    }

    private SchemaDriftFindingView findingView() {
        return new SchemaDriftFindingView(findingId, orgId, scanId, environmentId, "public.orders.email",
                SchemaDriftFindingKind.TYPE_MISMATCH, "text", "int4", SchemaDriftFindingStatus.OPEN,
                NOW, NOW, null);
    }

    private SchemaDriftConfigView configView() {
        return new SchemaDriftConfigView(UUID.randomUUID(), orgId, pipelineId, true,
                SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null, 24, null, null);
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }
}
