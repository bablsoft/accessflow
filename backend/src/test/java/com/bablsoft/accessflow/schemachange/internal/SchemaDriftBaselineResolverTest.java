package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftBaselineResolverTest {

    @Mock DeploymentEnvironmentLookupService environmentLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock SchemaChangeSetPromotionRepository promotionRepository;

    private SchemaDriftBaselineResolver resolver;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID devId = UUID.randomUUID();
    private final UUID stagingId = UUID.randomUUID();
    private final UUID prodId = UUID.randomUUID();
    private final UUID devDsId = UUID.randomUUID();
    private final UUID prodDsId = UUID.randomUUID();

    private static final DatabaseSchemaView SCHEMA = new DatabaseSchemaView(List.of(
            new DatabaseSchemaView.Schema("public", List.of(new DatabaseSchemaView.Table("orders",
                    List.of(new DatabaseSchemaView.Column("id", "uuid", false, true)), List.of())))));

    @BeforeEach
    void setUp() {
        resolver = new SchemaDriftBaselineResolver(environmentLookupService, datasourceAdminService,
                promotionRepository, new ObjectMapper());
    }

    private DeploymentEnvironmentView environment(UUID id, String name, int sortOrder, UUID datasourceId) {
        return new DeploymentEnvironmentView(id, pipelineId, name, sortOrder, false, null, null, false,
                Instant.EPOCH, List.of(), datasourceId);
    }

    private DeploymentEnvironmentView environment(UUID id, String name, int sortOrder, UUID datasourceId,
                                                  UUID owningPipelineId) {
        return new DeploymentEnvironmentView(id, owningPipelineId, name, sortOrder, false, null, null, false,
                Instant.EPOCH, List.of(), datasourceId);
    }

    private SchemaDriftScanContext ctx(SchemaDriftBaseline baseline, UUID baselineEnvironmentId) {
        return new SchemaDriftScanContext(orgId, pipelineId, prodId, prodDsId, DbType.POSTGRESQL,
                baseline, baselineEnvironmentId);
    }

    private void stubDatasource(UUID datasourceId, DbType dbType) {
        var view = org.mockito.Mockito.mock(DatasourceView.class);
        lenient().when(view.dbType()).thenReturn(dbType);
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view);
    }

    // --- PREVIOUS_ENVIRONMENT ------------------------------------------------------------------

    @Test
    void previousEnvironmentResolvesTheHighestLowerBoundRung() {
        when(environmentLookupService.findById(prodId))
                .thenReturn(Optional.of(environment(prodId, "prod", 30, prodDsId)));
        var stagingDsId = UUID.randomUUID();
        // staging (20) is the adjacent lower rung, not dev (10).
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(stagingId, "staging", 20, stagingDsId),
                environment(prodId, "prod", 30, prodDsId)));
        stubDatasource(stagingDsId, DbType.POSTGRESQL);
        when(datasourceAdminService.introspectSchemaForSystem(stagingDsId, orgId)).thenReturn(SCHEMA);

        var resolution = resolver.resolve(ctx(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null));

        assertThat(resolution.baseline()).isSameAs(SCHEMA);
        assertThat(resolution.reasonCode()).isNull();
    }

    @Test
    void previousEnvironmentSkipsDeployOnlyRungsRatherThanStoppingAtThem() {
        when(environmentLookupService.findById(prodId))
                .thenReturn(Optional.of(environment(prodId, "prod", 30, prodDsId)));
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(devId, "dev", 10, devDsId),
                environment(stagingId, "staging", 20, null),   // deploy-only: skipped, not a wall
                environment(prodId, "prod", 30, prodDsId)));
        stubDatasource(devDsId, DbType.POSTGRESQL);
        when(datasourceAdminService.introspectSchemaForSystem(devDsId, orgId)).thenReturn(SCHEMA);

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null)).baseline())
                .isSameAs(SCHEMA);
    }

    @Test
    void previousEnvironmentWithNoLowerBoundRungIsUnresolved() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, devDsId)));
        when(environmentLookupService.listByPipeline(pipelineId))
                .thenReturn(List.of(environment(devId, "dev", 10, devDsId)));

        var ctx = new SchemaDriftScanContext(orgId, pipelineId, devId, devDsId, DbType.POSTGRESQL,
                SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null);

        assertThat(resolver.resolve(ctx).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND);
        verify(datasourceAdminService, never()).introspectSchemaForSystem(any(), any());
    }

    @Test
    void previousEnvironmentIsUnresolvedWhenTheScannedEnvironmentIsGone() {
        when(environmentLookupService.findById(prodId)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND);
    }

    // --- BASELINE_ENVIRONMENT ------------------------------------------------------------------

    @Test
    void baselineEnvironmentResolvesTheDesignatedReference() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, devDsId)));
        stubDatasource(devDsId, DbType.POSTGRESQL);
        when(datasourceAdminService.introspectSchemaForSystem(devDsId, orgId)).thenReturn(SCHEMA);

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).baseline())
                .isSameAs(SCHEMA);
    }

    @Test
    void baselineEnvironmentWithNoDesignationIsUnresolved() {
        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENVIRONMENT_NOT_CONFIGURED);
    }

    @Test
    void theDesignatedEnvironmentScanningItselfIsUnresolvedRatherThanVacuouslyClean() {
        // The designation is pipeline-wide, so the job reaches this rung on every run.
        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, prodId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENVIRONMENT_IS_TARGET);
        verify(environmentLookupService, never()).findById(any());
    }

    @Test
    void aDeletedDesignatedEnvironmentIsUnresolved() {
        when(environmentLookupService.findById(devId)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENVIRONMENT_NOT_FOUND);
    }

    @Test
    void aDesignatedEnvironmentOnAnotherPipelineReadsAsAbsent() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, devDsId, UUID.randomUUID())));

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENVIRONMENT_NOT_FOUND);
    }

    @Test
    void anUnboundDesignatedEnvironmentIsUnresolved() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, null)));

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENVIRONMENT_NO_DATASOURCE);
    }

    @Test
    void aDifferentEngineOnTheBaselineIsUnresolved() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, devDsId)));
        stubDatasource(devDsId, DbType.MYSQL);

        // Comparing across engines would report a type mismatch on every column, forever.
        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_ENGINE_MISMATCH);
        verify(datasourceAdminService, never()).introspectSchemaForSystem(any(), any());
    }

    @Test
    void aFailingBaselineIntrospectionIsUnresolvedRatherThanThrown() {
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 10, devDsId)));
        stubDatasource(devDsId, DbType.POSTGRESQL);
        when(datasourceAdminService.introspectSchemaForSystem(devDsId, orgId))
                .thenThrow(new IllegalStateException("connection refused"));

        // The scanned environment is healthy; it is the reference that is not.
        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.BASELINE_ENVIRONMENT, devId)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_INTROSPECTION_FAILED);
    }

    // --- PROMOTION_SNAPSHOT --------------------------------------------------------------------

    private SchemaChangeSetPromotionEntity promotion(UUID datasourceId, String snapshot) {
        var entity = new SchemaChangeSetPromotionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setEnvironmentId(prodId);
        entity.setDatasourceId(datasourceId);
        entity.setSchemaSnapshot(snapshot);
        return entity;
    }

    private void stubPromotion(SchemaChangeSetPromotionEntity entity) {
        when(promotionRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndStatusOrderByAppliedAtDesc(
                        eq(orgId), eq(prodId), eq(SchemaChangePromotionStatus.APPLIED)))
                .thenReturn(Optional.ofNullable(entity));
    }

    @Test
    void promotionSnapshotDeserializesTheStoredView() {
        stubPromotion(promotion(prodDsId, new ObjectMapper().writeValueAsString(SCHEMA)));

        var resolution = resolver.resolve(ctx(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null));

        assertThat(resolution.reasonCode()).isNull();
        assertThat(resolution.baseline().schemas()).hasSize(1);
        assertThat(resolution.baseline().schemas().getFirst().tables().getFirst().name()).isEqualTo("orders");
        // No second database is contacted in this mode.
        verify(datasourceAdminService, never()).introspectSchemaForSystem(any(), any());
    }

    @Test
    void aNewestPromotionWithoutASnapshotIsUnresolvedRatherThanFallingBackToAnOlderOne() {
        // An older promotion's snapshot would report the newest change set's own DDL as drift.
        stubPromotion(promotion(prodDsId, null));

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING);
    }

    @Test
    void noAppliedPromotionWithASnapshotIsUnresolved() {
        stubPromotion(null);

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING);
    }

    @Test
    void aReboundDatasourceInvalidatesTheSnapshot() {
        stubPromotion(promotion(UUID.randomUUID(), new ObjectMapper().writeValueAsString(SCHEMA)));

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_DATASOURCE_REBOUND);
    }

    @Test
    void anUnreadableSnapshotIsUnresolvedRatherThanThrown() {
        stubPromotion(promotion(prodDsId, "{not json"));

        assertThat(resolver.resolve(ctx(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null)).reasonCode())
                .isEqualTo(SchemaDriftScanReason.BASELINE_SNAPSHOT_UNREADABLE);
    }
}
