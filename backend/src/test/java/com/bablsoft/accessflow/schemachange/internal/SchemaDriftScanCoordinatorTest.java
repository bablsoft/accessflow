package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftScanCoordinatorTest {

    @Mock DeploymentEnvironmentLookupService environmentLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock SchemaDriftScanService scanService;
    @Mock SchemaDriftScanStore scanStore;

    private SchemaDriftScanCoordinator coordinator;

    private static final SchemaDriftScanRun RAN = new SchemaDriftScanRun(true, null);

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID devId = UUID.randomUUID();
    private final UUID prodId = UUID.randomUUID();
    private final UUID devDsId = UUID.randomUUID();
    private final UUID prodDsId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        coordinator = new SchemaDriftScanCoordinator(environmentLookupService, datasourceAdminService,
                scanService, scanStore);
    }

    private SchemaDriftConfigEntity config() {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setPipelineId(pipelineId);
        config.setEnabled(true);
        config.setBaseline(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        return config;
    }

    private DeploymentEnvironmentView environment(UUID id, String name, int sortOrder, UUID datasourceId) {
        return new DeploymentEnvironmentView(id, pipelineId, name, sortOrder, false, null, null, false,
                Instant.EPOCH, List.of(), datasourceId);
    }

    private void stubDatasource(UUID datasourceId) {
        var view = org.mockito.Mockito.mock(DatasourceView.class);
        lenient().when(view.id()).thenReturn(datasourceId);
        lenient().when(view.dbType()).thenReturn(DbType.POSTGRESQL);
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view);
    }

    @Test
    void scansEverySchemaBoundEnvironmentOfThePipeline() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(prodId, "prod", 20, prodDsId)));
        stubDatasource(devDsId);
        stubDatasource(prodDsId);
        when(scanService.scan(any())).thenReturn(RAN);

        assertThat(coordinator.scanPipeline(config())).isEqualTo(2);
    }

    @Test
    void deployOnlyEnvironmentsAreSkippedWithoutTouchingTheDatasourceService() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, null)));

        assertThat(coordinator.scanPipeline(config())).isZero();
        verify(datasourceAdminService, never()).getForAdmin(any(), any());
        verify(scanService, never()).scan(any());
    }

    @Test
    void anEnvironmentBoundToAMissingDatasourceDoesNotAbortItsSiblings() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(prodId, "prod", 20, prodDsId)));
        when(datasourceAdminService.getForAdmin(devDsId, orgId))
                .thenThrow(new DatasourceNotFoundException(devDsId));
        stubDatasource(prodDsId);
        when(scanService.scan(any())).thenReturn(RAN);

        assertThat(coordinator.scanPipeline(config())).isEqualTo(1);
    }

    @Test
    void anEnvironmentLockedElsewhereIsNotCountedAndIsNotAnError() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId)));
        stubDatasource(devDsId);
        when(scanService.scan(any())).thenReturn(SchemaDriftScanRun.SKIPPED);

        assertThat(coordinator.scanPipeline(config())).isZero();
    }

    @Test
    void theContextCarriesThePipelineConfigurationNotTheEnvironmentDefaults() {
        var config = config();
        var baselineEnvironmentId = UUID.randomUUID();
        config.setBaseline(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        config.setBaselineEnvironmentId(baselineEnvironmentId);
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(prodId, "prod", 20, prodDsId)));
        stubDatasource(prodDsId);
        when(scanService.scan(any())).thenReturn(RAN);

        coordinator.scanPipeline(config);

        var captor = ArgumentCaptor.forClass(SchemaDriftScanContext.class);
        verify(scanService).scan(captor.capture());
        assertThat(captor.getValue()).satisfies(ctx -> {
            assertThat(ctx.organizationId()).isEqualTo(orgId);
            assertThat(ctx.pipelineId()).isEqualTo(pipelineId);
            assertThat(ctx.environmentId()).isEqualTo(prodId);
            assertThat(ctx.datasourceId()).isEqualTo(prodDsId);
            assertThat(ctx.dbType()).isEqualTo(DbType.POSTGRESQL);
            assertThat(ctx.baseline()).isEqualTo(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
            assertThat(ctx.baselineEnvironmentId()).isEqualTo(baselineEnvironmentId);
        });
    }

    @Test
    void aPipelineWithNoEnvironmentsScansNothing() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of());

        assertThat(coordinator.scanPipeline(config())).isZero();
    }

    // --- the pipeline stamp ---------------------------------------------------------------------

    @Test
    void thePipelineIsStampedOnceAfterEveryEnvironmentWasVisited() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(prodId, "prod", 20, prodDsId)));
        stubDatasource(devDsId);
        stubDatasource(prodDsId);
        when(scanService.scan(any())).thenReturn(RAN);

        coordinator.scanPipeline(config());

        verify(scanStore, org.mockito.Mockito.times(1)).stampConfig(pipelineId, orgId, null);
    }

    @Test
    void everyFailingEnvironmentIsNamedNotJustTheLastToFinish() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(prodId, "prod", 20, prodDsId)));
        stubDatasource(devDsId);
        stubDatasource(prodDsId);
        // dev fails, prod succeeds afterwards: the success must not erase the failure.
        when(scanService.scan(any())).thenReturn(
                new SchemaDriftScanRun(true, SchemaDriftScanReason.TARGET_INTROSPECTION_FAILED + ": refused"),
                RAN);

        coordinator.scanPipeline(config());

        verify(scanStore).stampConfig(pipelineId, orgId,
                "dev: " + SchemaDriftScanReason.TARGET_INTROSPECTION_FAILED + ": refused");
    }

    @Test
    void anEnvironmentThatThrowsIsRecordedAsAFailureOfThePipelineRun() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId)));
        when(datasourceAdminService.getForAdmin(devDsId, orgId))
                .thenThrow(new DatasourceNotFoundException(devDsId));

        coordinator.scanPipeline(config());

        verify(scanStore).stampConfig(pipelineId, orgId, "dev: " + SchemaDriftScanReason.SCAN_FAILED);
    }

    @Test
    void aPipelineWithAnEnvironmentLockedElsewhereIsLeftDue() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(prodId, "prod", 20, prodDsId)));
        stubDatasource(devDsId);
        stubDatasource(prodDsId);
        when(scanService.scan(any())).thenReturn(RAN, SchemaDriftScanRun.SKIPPED);

        coordinator.scanPipeline(config());

        // Stamping would make prod wait a whole interval for a scan that never ran here.
        verify(scanStore, never()).stampConfig(any(), any(), any());
    }
}
