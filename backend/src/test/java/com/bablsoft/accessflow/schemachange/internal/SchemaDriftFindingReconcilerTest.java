package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftFindingReconcilerTest {

    private final java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-01T10:00:00Z");
    private static final String PATH = "public.orders.email";

    @Mock SchemaDriftFindingRepository findingRepository;

    private SchemaDriftFindingReconciler reconciler;

    private final UUID orgId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private SchemaDriftScanEntity scan;
    private SchemaDriftScanContext ctx;

    @BeforeEach
    void setUp() {
        reconciler = new SchemaDriftFindingReconciler(findingRepository);
        scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        ctx = new SchemaDriftScanContext(orgId, UUID.randomUUID(), environmentId, UUID.randomUUID(),
                DbType.POSTGRESQL, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null);
        lenient().when(findingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void stubActive(SchemaDriftFindingEntity... findings) {
        when(findingRepository.findAllByOrganizationIdAndEnvironmentIdAndStatusIn(eq(orgId),
                eq(environmentId), anyCollection())).thenReturn(List.of(findings));
    }

    private SchemaDriftFindingEntity existing(SchemaDriftFindingStatus status, String expected, String actual) {
        var entity = new SchemaDriftFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setEnvironmentId(environmentId);
        entity.setObjectPath(PATH);
        entity.setFindingKind(SchemaDriftFindingKind.TYPE_MISMATCH);
        entity.setExpectedValue(expected);
        entity.setActualValue(actual);
        entity.setStatus(status);
        entity.setFirstDetectedAt(EARLIER);
        entity.setLastSeenAt(EARLIER);
        var previousScan = new SchemaDriftScanEntity();
        previousScan.setId(UUID.randomUUID());
        entity.setScan(previousScan);
        return entity;
    }

    private static SchemaDriftDiffer.DriftFinding finding(String expected, String actual) {
        return new SchemaDriftDiffer.DriftFinding(PATH, SchemaDriftFindingKind.TYPE_MISMATCH, expected, actual);
    }

    private static SchemaDriftDiffer.DiffResult result(List<SchemaDriftDiffer.DriftFinding> findings,
                                                       Set<String> reached) {
        return result(findings, reached, reached);
    }

    private static SchemaDriftDiffer.DiffResult result(List<SchemaDriftDiffer.DriftFinding> findings,
                                                       Set<String> reached, Set<String> known) {
        return new SchemaDriftDiffer.DiffResult(findings, reached, known, true, false, false);
    }

    // --- newly observed -------------------------------------------------------------------------

    @Test
    void anUnseenFindingIsCreatedOpen() {
        stubActive();
        when(findingRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
                        any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int4")),
                Set.of("public.orders")), NOW, opened::incrementAndGet);

        // #882: a created row is new drift — it is what SCHEMA_DRIFT_DETECTED counts.
        assertThat(opened.get()).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(SchemaDriftFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        assertThat(captor.getValue()).satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
            assertThat(saved.getFirstDetectedAt()).isEqualTo(NOW);
            assertThat(saved.getLastSeenAt()).isEqualTo(NOW);
            assertThat(saved.getResolvedAt()).isNull();
            assertThat(saved.getScan()).isSameAs(scan);
        });
    }

    // --- still observed -------------------------------------------------------------------------

    @Test
    void anOpenFindingIsRepointedAndItsFirstDetectionPreserved() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int8")),
                Set.of("public.orders")), NOW, opened::incrementAndGet);

        // #882: a finding merely re-seen is never new drift, or a persistent one alerts every scan.
        assertThat(opened.get()).isZero();
        assertThat(open.getScan()).isSameAs(scan);
        assertThat(open.getLastSeenAt()).isEqualTo(NOW);
        assertThat(open.getFirstDetectedAt()).isEqualTo(EARLIER);
        assertThat(open.getActualValue()).isEqualTo("int8");
    }

    @Test
    void anAcknowledgedFindingWithUnchangedValuesStaysAcknowledged() {
        var acknowledged = existing(SchemaDriftFindingStatus.ACKNOWLEDGED, "text", "int4");
        stubActive(acknowledged);

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int4")), Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(acknowledged.getStatus()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(acknowledged.getLastSeenAt()).isEqualTo(NOW);
        assertThat(opened.get()).isZero();
    }

    @Test
    void caseOnlyValueChurnDoesNotReopenAnAcknowledgement() {
        var acknowledged = existing(SchemaDriftFindingStatus.ACKNOWLEDGED, "text", "int4");
        stubActive(acknowledged);

        // A driver that starts reporting INT4 where it reported int4 is not a new difference.
        reconciler.reconcile(scan, ctx, result(List.of(finding("TEXT", "INT4")), Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(acknowledged.getStatus()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
    }

    @Test
    void anAcknowledgedFindingWhoseValuesChangedReopens() {
        var acknowledged = existing(SchemaDriftFindingStatus.ACKNOWLEDGED, "text", "int4");
        stubActive(acknowledged);

        // The admin accepted varchar-vs-text, not text-vs-bigint.
        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int8")),
                Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(acknowledged.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
        // #882: an acknowledgement accepted the old difference, not this one — it is new drift.
        assertThat(opened.get()).isEqualTo(1);
        assertThat(acknowledged.getActualValue()).isEqualTo("int8");
    }

    @Test
    void aResolvedFindingThatReappearsIsReopenedInPlace() {
        var resolved = existing(SchemaDriftFindingStatus.RESOLVED, "text", "int4");
        resolved.setResolvedAt(EARLIER);
        stubActive();
        when(findingRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
                        eq(orgId), eq(environmentId), eq(PATH), eq(SchemaDriftFindingKind.TYPE_MISMATCH)))
                .thenReturn(Optional.of(resolved));

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int4")),
                Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(resolved.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
        // #882: a drift that had gone away and came back is a new episode — it counts.
        assertThat(opened.get()).isEqualTo(1);
        assertThat(resolved.getResolvedAt()).isNull();
        // "First ever observed" is what makes a flapping object visible; resetting it would hide it.
        assertThat(resolved.getFirstDetectedAt()).isEqualTo(EARLIER);
        assertThat(resolved.getLastSeenAt()).isEqualTo(NOW);
    }

    // --- no longer observed ---------------------------------------------------------------------

    @Test
    void aDisappearedFindingUnderAComparedTableIsResolved() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        var previousScan = open.getScan();
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
        assertThat(open.getResolvedAt()).isEqualTo(NOW);
        // Both fields describe observation, not bookkeeping, so neither moves on a resolve.
        assertThat(open.getScan()).isSameAs(previousScan);
        assertThat(open.getLastSeenAt()).isEqualTo(EARLIER);
    }

    @Test
    void anAcknowledgedFindingCanAlsoBeResolved() {
        var acknowledged = existing(SchemaDriftFindingStatus.ACKNOWLEDGED, "text", "int4");
        stubActive(acknowledged);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("public.orders")), NOW, opened::incrementAndGet);

        assertThat(acknowledged.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    @Test
    void aFindingUnderATableTheScanNeverReachedIsLeftAlone() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);

        // The table cap or the time budget skipped public.orders: "we did not look" is not "it is
        // fixed".
        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("public.invoices"),
                Set.of("public.orders", "public.invoices")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
        assertThat(open.getResolvedAt()).isNull();
        verify(findingRepository, never()).save(any());
    }

    @Test
    void aSchemaLevelFindingIsAlwaysEligibleToResolve() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "reporting (2 tables)", null);
        open.setObjectPath("reporting");
        open.setFindingKind(SchemaDriftFindingKind.MISSING_IN_TARGET);
        stubActive(open);

        // The schema-set comparison is O(1) and always completes, so it needs no reached table.
        reconciler.reconcile(scan, ctx, result(List.of(), Set.of()), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    @Test
    void aTableLevelFindingResolvesOnItsOwnTableKey() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "archive (3 columns)", null);
        open.setObjectPath("public.archive");
        open.setFindingKind(SchemaDriftFindingKind.MISSING_IN_TARGET);
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("public.archive")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    @Test
    void nothingIsResolvedWhenTheSchemaComparisonNeverRan() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);

        var incomplete = new SchemaDriftDiffer.DiffResult(List.of(), Set.of("public.orders"),
                Set.of("public.orders"), false, false, true);
        reconciler.reconcile(scan, ctx, incomplete, NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
    }

    // --- dotted names (Elasticsearch, OpenSearch, BigQuery) -------------------------------------

    @Test
    void aFindingOnANestedDottedFieldResolvesUnderItsRealTable() {
        // Elasticsearch flattens nested fields into dot-paths, so the path cannot be split on its last
        // dot: that would look for a table "default.orders.customer" that never exists.
        var open = existing(SchemaDriftFindingStatus.OPEN, "keyword", "text");
        open.setObjectPath("default.orders.customer.id");
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("default.orders")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    @Test
    void aTableLevelFindingOnADottedIndexNameResolves() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "logs (3 columns)", null);
        open.setObjectPath("default.logs-2026.09.23");
        open.setFindingKind(SchemaDriftFindingKind.MISSING_IN_TARGET);
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("default.logs-2026.09.23")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    @Test
    void theLongestKnownTablePrefixDecidesAndAnUnreachedDottedTableKeepsItsFindings() {
        // Both "default.orders" and the dotted table "default.orders.archive" exist; the finding belongs
        // to the longer one, which the scan never reached.
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        open.setObjectPath("default.orders.archive.total");
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of("default.orders"),
                Set.of("default.orders", "default.orders.archive")), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
    }

    @Test
    void aFindingUnderATableNeitherSideHasAnyMoreResolves() {
        // Dropped from both databases, or under a schema one side lacks: the schema comparison covers
        // it, so nothing is left to re-observe.
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);

        reconciler.reconcile(scan, ctx, result(List.of(), Set.of(), Set.of()), NOW, opened::incrementAndGet);

        assertThat(open.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    // --- suppressed foreign keys ----------------------------------------------------------------

    @Test
    void aForeignKeyFindingIsLeftAloneWhenForeignKeysWereNotCompared() {
        var fk = existing(SchemaDriftFindingStatus.ACKNOWLEDGED, "customers.id", "(none)");
        fk.setFindingKind(SchemaDriftFindingKind.FOREIGN_KEY_MISMATCH);
        var type = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        type.setObjectPath("public.orders.total");
        stubActive(fk, type);

        // "We did not look" must not become "it is fixed" — and must not lose the acknowledgement.
        var suppressed = new SchemaDriftDiffer.DiffResult(List.of(), Set.of("public.orders"),
                Set.of("public.orders"), true, true, false);
        reconciler.reconcile(scan, ctx, suppressed, NOW, opened::incrementAndGet);

        assertThat(fk.getStatus()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(type.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
    }

    // --- concurrent acknowledgement -------------------------------------------------------------

    @Test
    void aRowChangedDuringTheScanIsSkippedRatherThanOverwritten() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);
        when(findingRepository.save(open))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        SchemaDriftFindingEntity.class, open.getId()));

        org.assertj.core.api.Assertions.assertThatCode(() -> reconciler.reconcile(scan, ctx,
                        result(List.of(finding("text", "int8")), Set.of("public.orders")), NOW, opened::incrementAndGet))
                .doesNotThrowAnyException();
    }

    @Test
    void aSkippedStillPresentFindingIsNotResolvedEither() {
        var open = existing(SchemaDriftFindingStatus.OPEN, "text", "int4");
        stubActive(open);
        when(findingRepository.save(open))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        SchemaDriftFindingEntity.class, open.getId()));

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int8")), Set.of("public.orders")), NOW, opened::incrementAndGet);

        // Saved once (the refresh, which lost); never a second save marking it resolved.
        verify(findingRepository).save(open);
        assertThat(open.getResolvedAt()).isNull();
    }

    @Test
    void aReopenThatLosesTheRaceIsNotCounted() {
        var resolved = existing(SchemaDriftFindingStatus.RESOLVED, "text", "int4");
        stubActive();
        when(findingRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
                        eq(orgId), eq(environmentId), eq(PATH), eq(SchemaDriftFindingKind.TYPE_MISMATCH)))
                .thenReturn(Optional.of(resolved));
        when(findingRepository.save(resolved))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        SchemaDriftFindingEntity.class, resolved.getId()));

        reconciler.reconcile(scan, ctx, result(List.of(finding("text", "int4")),
                Set.of("public.orders")), NOW, opened::incrementAndGet);
        assertThat(opened.get()).isZero();
    }
}
