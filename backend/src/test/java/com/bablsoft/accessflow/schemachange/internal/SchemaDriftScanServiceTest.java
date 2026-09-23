package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock SchemaDriftScanStore scanStore;
    @Mock SchemaDriftScanRepository scanRepository;
    @Mock SchemaDriftBaselineResolver baselineResolver;
    @Mock SchemaDriftFindingReconciler reconciler;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DistributedLockService distributedLockService;
    @Mock SchemaChangeAuditWriter auditWriter;

    private ExecutorService executor;
    private SchemaDriftScanEntity scan;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private static final DatabaseSchemaView TARGET = new DatabaseSchemaView(List.of(
            new DatabaseSchemaView.Schema("public", List.of(new DatabaseSchemaView.Table("orders",
                    List.of(new DatabaseSchemaView.Column("id", "uuid", false, true)), List.of())))));
    private static final DatabaseSchemaView BASELINE = new DatabaseSchemaView(List.of(
            new DatabaseSchemaView.Schema("public", List.of(new DatabaseSchemaView.Table("orders",
                    List.of(new DatabaseSchemaView.Column("id", "text", false, true)), List.of())))));

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        lenient().when(scanStore.open(any())).thenReturn(scan);
        lenient().when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        // The lock behaviour itself is pinned in DefaultDistributedLockServiceTest.
        lenient().when(distributedLockService.runLocked(any(), any(), any())).thenAnswer(i -> {
            i.getArgument(2, Runnable.class).run();
            return true;
        });
        lenient().when(distributedLockService.runLockedAsync(any(), any(), any(), any())).thenAnswer(i -> {
            i.getArgument(3, Runnable.class).run();
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** Properties are a real record, never a mock — the coercion rules are part of the behaviour. */
    private SchemaDriftScanService service(SchemaChangeProperties properties, Clock clock) {
        return new SchemaDriftScanService(scanStore, scanRepository, baselineResolver, reconciler,
                datasourceAdminService, distributedLockService, auditWriter, properties, executor, clock);
    }

    private SchemaDriftScanService service() {
        return service(new SchemaChangeProperties(null, null, null, null, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SchemaDriftScanContext ctx(DbType dbType) {
        return ctx(dbType, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
    }

    private SchemaDriftScanContext ctx(DbType dbType, SchemaDriftBaseline baseline) {
        return new SchemaDriftScanContext(orgId, pipelineId, environmentId, datasourceId, dbType, baseline, null);
    }

    /** Advances on every read, so a time budget can be driven without sleeping. */
    private static Clock steppingClock(Duration step) {
        return new Clock() {
            private Instant current = NOW;

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                var now = current;
                current = current.plus(step);
                return now;
            }
        };
    }

    /** Same schema, no tables — so the walk reaches the table loop instead of stopping at a
     * missing schema, which the differ deliberately never descends past. */
    private static DatabaseSchemaView emptyPublicSchema() {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", List.of())));
    }

    private static DatabaseSchemaView threeTables() {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", List.of(
                new DatabaseSchemaView.Table("a", List.of(), List.of()),
                new DatabaseSchemaView.Table("b", List.of(), List.of()),
                new DatabaseSchemaView.Table("c", List.of(), List.of())))));
    }

    private void stubBaseline(DatabaseSchemaView baseline, DatabaseSchemaView target) {
        when(baselineResolver.resolve(any())).thenReturn(SchemaDriftBaselineResolution.resolved(baseline));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, orgId)).thenReturn(target);
    }

    private String finishedReason() {
        var reason = ArgumentCaptor.forClass(String.class);
        verify(scanStore).finish(eq(scan.getId()), anyBoolean(), anyBoolean(), reason.capture());
        return reason.getValue();
    }

    // --- applicability ---------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"MONGODB", "REDIS", "COUCHBASE", "DYNAMODB", "NEO4J"})
    void aSamplingEngineIsNotApplicableAndIsNeverContacted(DbType samplingEngine) {
        service().scan(ctx(samplingEngine));

        // Not merely "not diffed" — no connection is opened at all.
        verifyNoInteractions(datasourceAdminService, baselineResolver, reconciler);
        verify(scanStore).finish(scan.getId(), false, false, SchemaDriftScanReason.ENGINE_NOT_APPLICABLE);
    }

    @ParameterizedTest
    @EnumSource(value = DbType.class,
            names = {"POSTGRESQL", "MYSQL", "MARIADB", "ORACLE", "MSSQL", "CUSTOM", "CASSANDRA",
                    "SCYLLADB", "ELASTICSEARCH", "OPENSEARCH", "SNOWFLAKE", "BIGQUERY", "DATABRICKS"})
    void everyCatalogEngineIsApplicable(DbType catalogEngine) {
        assertThat(SchemaDriftScanService.DETERMINISTIC_ENGINES).contains(catalogEngine);
    }

    @Test
    void theAllowListClassifiesThirteenOfTheEighteenEngines() {
        // A DbType added later is not applicable until somebody verifies its introspector — the safe
        // direction to be wrong in.
        assertThat(SchemaDriftScanService.DETERMINISTIC_ENGINES).hasSize(13);
        assertThat(DbType.values()).hasSize(18);
    }

    // --- the baseline ----------------------------------------------------------------------------

    @Test
    void anUnresolvedBaselineRecordsAReasonWithoutContactingTheTarget() {
        when(baselineResolver.resolve(any())).thenReturn(
                SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING));

        service().scan(ctx(DbType.POSTGRESQL));

        // Never a silent pass, never a resolve sweep, and no connection opened just to record that.
        assertThat(finishedReason()).isEqualTo(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING);
        verifyNoInteractions(reconciler, datasourceAdminService);
    }

    @ParameterizedTest
    @EnumSource(SchemaDriftBaseline.class)
    void everyBaselineModeRunsTheSameDiffOnceResolved(SchemaDriftBaseline baseline) {
        stubBaseline(BASELINE, TARGET);

        service().scan(ctx(DbType.POSTGRESQL, baseline));

        verify(reconciler).reconcile(eq(scan), any(), any(), eq(NOW));
        verify(scanStore).finish(scan.getId(), true, false, null);
    }

    @Test
    void theBaselineIsResolvedBeforeTheTargetIsIntrospected() {
        stubBaseline(BASELINE, TARGET);

        service().scan(ctx(DbType.POSTGRESQL));

        InOrder order = inOrder(baselineResolver, datasourceAdminService);
        order.verify(baselineResolver).resolve(any());
        order.verify(datasourceAdminService).introspectSchemaForSystem(datasourceId, orgId);
    }

    @Test
    void aResolvedBaselineIsDiffedAndReconciled() {
        stubBaseline(BASELINE, TARGET);

        service().scan(ctx(DbType.POSTGRESQL));

        var captor = ArgumentCaptor.forClass(SchemaDriftDiffer.DiffResult.class);
        verify(reconciler).reconcile(eq(scan), any(), captor.capture(), eq(NOW));
        assertThat(captor.getValue().findings()).singleElement()
                .satisfies(f -> assertThat(f.objectPath()).isEqualTo("public.orders.id"));
        assertThat(captor.getValue().reachedTableKeys()).isEqualTo(Set.of("public.orders"));
    }

    @Test
    void suppressedForeignKeysAreRecordedAsAReason() {
        var withKeys = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders",
                        List.of(new DatabaseSchemaView.Column("id", "uuid", false, true)),
                        List.of(new DatabaseSchemaView.ForeignKey("id", "customers", "id")))))));
        stubBaseline(withKeys, TARGET);

        service().scan(ctx(DbType.POSTGRESQL));

        assertThat(finishedReason()).isEqualTo(SchemaDriftScanReason.FK_COMPARISON_SUPPRESSED);
    }

    // --- caps and the budget ---------------------------------------------------------------------

    @Test
    void theTableCapFlagsTheScanPartial() {
        stubBaseline(threeTables(), emptyPublicSchema());

        service(new SchemaChangeProperties(null, null, null, 1, null, null), Clock.fixed(NOW, ZoneOffset.UTC))
                .scan(ctx(DbType.POSTGRESQL));

        verify(scanStore).finish(eq(scan.getId()), eq(true), eq(true), any());
    }

    @Test
    void theTimeBudgetFlagsTheScanPartial() {
        stubBaseline(threeTables(), emptyPublicSchema());

        service(new SchemaChangeProperties(null, null, Duration.ofSeconds(1), null, null, null),
                steppingClock(Duration.ofSeconds(10))).scan(ctx(DbType.POSTGRESQL));

        verify(scanStore).finish(eq(scan.getId()), eq(true), eq(true), any());
    }

    // --- failure ---------------------------------------------------------------------------------

    @Test
    void aFailingTargetIntrospectionIsNamedAsSuchAndReconcilesNothing() {
        when(baselineResolver.resolve(any())).thenReturn(SchemaDriftBaselineResolution.resolved(BASELINE));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, orgId))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThatCode(() -> service().scan(ctx(DbType.POSTGRESQL))).doesNotThrowAnyException();

        assertThat(finishedReason())
                .isEqualTo(SchemaDriftScanReason.TARGET_INTROSPECTION_FAILED + ": connection refused");
        verifyNoInteractions(reconciler);
    }

    @Test
    void aFailureInsideAccessFlowIsNotBlamedOnTheCustomerDatabase() {
        stubBaseline(BASELINE, TARGET);
        doThrow(new IllegalStateException("db down")).when(reconciler).reconcile(any(), any(), any(), any());

        var run = service().scan(ctx(DbType.POSTGRESQL));

        assertThat(finishedReason()).isEqualTo(SchemaDriftScanReason.SCAN_FAILED + ": db down");
        assertThat(run.failure()).isEqualTo(SchemaDriftScanReason.SCAN_FAILED + ": db down");
    }

    @Test
    void theAuditTailStillRunsWhenTheBodyThrew() {
        stubBaseline(BASELINE, TARGET);
        doThrow(new IllegalStateException("boom")).when(reconciler).reconcile(any(), any(), any(), any());

        service().scan(ctx(DbType.POSTGRESQL));

        verify(auditWriter).record(eq(AuditAction.SCHEMA_DRIFT_SCAN_COMPLETED),
                eq(AuditResourceType.SCHEMA_DRIFT_SCAN), eq(scan.getId()), eq(orgId), eq(null), any(),
                eq(null), eq(null));
    }

    @Test
    void aFailingFinishNeverEscapes() {
        when(baselineResolver.resolve(any())).thenReturn(
                SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING));
        doThrow(new IllegalStateException("db down")).when(scanStore).finish(any(), anyBoolean(), anyBoolean(), any());

        assertThatCode(() -> service().scan(ctx(DbType.POSTGRESQL))).doesNotThrowAnyException();
    }

    @Test
    void aTruncatedReasonNeverExceedsTheColumnBudget() {
        when(baselineResolver.resolve(any())).thenReturn(SchemaDriftBaselineResolution.resolved(BASELINE));
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, orgId))
                .thenThrow(new IllegalStateException("x".repeat(2000)));

        service().scan(ctx(DbType.POSTGRESQL));

        assertThat(finishedReason()).hasSize(500);
    }

    // --- what the scheduled path reports back ----------------------------------------------------

    @Test
    void aScheduledScanReportsNoFailureForAConfigurationState() {
        when(baselineResolver.resolve(any())).thenReturn(
                SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING));

        var run = service().scan(ctx(DbType.POSTGRESQL));

        // A missing baseline is a reason on the scan row, not a failure of the pipeline's run.
        assertThat(run.ran()).isTrue();
        assertThat(run.failure()).isNull();
    }

    @Test
    void aScheduledScanNeverStampsThePipelineItself() {
        stubBaseline(BASELINE, TARGET);

        service().scan(ctx(DbType.POSTGRESQL));

        // The configuration is per pipeline; only the coordinator, having visited every environment,
        // may stamp it.
        verify(scanStore, never()).stampConfig(any(), any(), any());
    }

    // --- the locks -------------------------------------------------------------------------------

    @Test
    void theScheduledPathLocksPerEnvironmentAndOpensNothingWhenItLoses() {
        doReturn(false).when(distributedLockService).runLocked(any(), any(), any());

        assertThat(service().scan(ctx(DbType.POSTGRESQL))).isSameAs(SchemaDriftScanRun.SKIPPED);

        verify(distributedLockService).runLocked(eq("schemaDriftScan:" + environmentId),
                eq(Duration.ofMinutes(30)), any());
        verifyNoInteractions(scanStore, datasourceAdminService, reconciler);
    }

    @Test
    void theOnDemandPathHandsTheScanToTheExecutorUnderTheSameLock() {
        stubBaseline(BASELINE, TARGET);

        assertThat(service().scanAsync(scan.getId(), ctx(DbType.POSTGRESQL), UUID.randomUUID())).isTrue();

        verify(distributedLockService).runLockedAsync(eq("schemaDriftScan:" + environmentId),
                eq(Duration.ofMinutes(30)), eq(executor), any());
    }

    @Test
    void theOnDemandPathReportsALostRace() {
        doReturn(false).when(distributedLockService).runLockedAsync(any(), any(), any(), any());

        assertThat(service().scanAsync(scan.getId(), ctx(DbType.POSTGRESQL), UUID.randomUUID())).isFalse();
        verifyNoInteractions(datasourceAdminService, reconciler);
    }

    // --- audit -----------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theAuditRowUsesTheModulesSnakeCaseKeysAndNamesTheTrigger() {
        stubBaseline(BASELINE, TARGET);
        when(scanStore.finish(any(), anyBoolean(), anyBoolean(), any())).thenReturn(3);
        var actorId = UUID.randomUUID();

        service().scanAsync(scan.getId(), ctx(DbType.POSTGRESQL), actorId);

        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(AuditAction.SCHEMA_DRIFT_SCAN_COMPLETED),
                eq(AuditResourceType.SCHEMA_DRIFT_SCAN), eq(scan.getId()), eq(orgId), eq(actorId),
                metadata.capture(), eq(null), eq(null));
        assertThat(metadata.getValue())
                .containsEntry("trigger", "manual")
                .containsEntry("applicable", true)
                .containsEntry("findings_count", 3)
                .containsEntry("baseline", "PREVIOUS_ENVIRONMENT")
                .containsEntry("environment_id", environmentId.toString())
                .containsKeys("pipeline_id", "datasource_id", "duration_ms");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anInapplicableScanSaysSoInTheAuditRow() {
        service().scan(ctx(DbType.REDIS));

        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(any(), any(), any(), any(), eq(null), metadata.capture(), any(), any());
        assertThat(metadata.getValue())
                .containsEntry("applicable", false)
                .containsEntry("trigger", "schedule")
                .containsEntry("reason", SchemaDriftScanReason.ENGINE_NOT_APPLICABLE);
    }
}
