package com.bablsoft.accessflow.schemachange;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftService;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that V178, V180 and V181 apply and every schemachange JPA entity maps to its table — entity ↔ DDL
 * parity under {@code ddl-auto=validate}, including the five new pg enum {@code columnDefinition}s
 * plus the shared {@code query_type}, the {@code char(64)} checksums and the jsonb snapshot, both
 * {@code ON DELETE CASCADE}s, the bulk statement delete, the two plain unique constraints and the partial unique index that
 * keeps at most one non-terminal promotion per change set and environment — booting the full
 * application context so the new module wires cleanly. Cross-module ids are bare UUIDs (no FK), so
 * no organization / pipeline / environment rows are needed.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SchemaChangePersistenceIntegrationTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Autowired
    private SchemaChangeSetRepository changeSetRepository;
    @Autowired
    private SchemaChangeSetStatementRepository statementRepository;
    @Autowired
    private SchemaChangeSetPromotionRepository promotionRepository;
    @Autowired
    private SchemaDriftScanRepository scanRepository;
    @Autowired
    private SchemaDriftFindingRepository findingRepository;
    @Autowired
    private SchemaDriftConfigRepository driftConfigRepository;
    @Autowired
    private SchemaDriftService driftService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private SchemaChangeSetEntity newChangeSet(UUID organizationId, UUID pipelineId, String name) {
        var set = new SchemaChangeSetEntity();
        set.setId(UUID.randomUUID());
        set.setOrganizationId(organizationId);
        set.setPipelineId(pipelineId);
        set.setName(name);
        set.setDescription("Adds the audit column");
        set.setCreatedBy(UUID.randomUUID());
        return set;
    }

    private SchemaChangeSetEntity savedChangeSet() {
        return changeSetRepository.saveAndFlush(
                newChangeSet(UUID.randomUUID(), UUID.randomUUID(), "cs-" + UUID.randomUUID()));
    }

    private SchemaChangeSetStatementEntity newStatement(SchemaChangeSetEntity set, int order) {
        var statement = new SchemaChangeSetStatementEntity();
        statement.setId(UUID.randomUUID());
        statement.setChangeSet(set);
        statement.setSequenceOrder(order);
        statement.setSqlText("ALTER TABLE orders ADD COLUMN audited_at TIMESTAMPTZ");
        statement.setQueryType(QueryType.DDL);
        return statement;
    }

    private SchemaChangeSetPromotionEntity newPromotion(SchemaChangeSetEntity set, UUID environmentId,
                                                        SchemaChangePromotionStatus status) {
        var promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(UUID.randomUUID());
        promotion.setOrganizationId(set.getOrganizationId());
        promotion.setChangeSet(set);
        promotion.setEnvironmentId(environmentId);
        promotion.setDatasourceId(UUID.randomUUID());
        promotion.setStatus(status);
        promotion.setStatementsChecksum(CHECKSUM);
        promotion.setPromotedBy(UUID.randomUUID());
        return promotion;
    }

    private SchemaDriftScanEntity newScan() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        scan.setOrganizationId(UUID.randomUUID());
        scan.setPipelineId(UUID.randomUUID());
        scan.setEnvironmentId(UUID.randomUUID());
        scan.setDatasourceId(UUID.randomUUID());
        scan.setBaseline(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
        return scan;
    }

    private SchemaDriftFindingEntity newFinding(SchemaDriftScanEntity scan, String objectPath) {
        var finding = new SchemaDriftFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(scan.getOrganizationId());
        finding.setScan(scan);
        finding.setEnvironmentId(scan.getEnvironmentId());
        finding.setObjectPath(objectPath);
        finding.setFindingKind(SchemaDriftFindingKind.TYPE_MISMATCH);
        finding.setExpectedValue("varchar");
        finding.setActualValue("text");
        return finding;
    }

    @Test
    void changeSetRoundTripsWithEveryColumn() {
        var set = newChangeSet(UUID.randomUUID(), UUID.randomUUID(), "cs-" + UUID.randomUUID());
        set.setStatus(SchemaChangeSetStatus.ACTIVE);
        set.setStatementsChecksum(CHECKSUM);
        changeSetRepository.saveAndFlush(set);

        var reloaded = changeSetRepository.findByIdAndOrganizationId(set.getId(), set.getOrganizationId()).orElseThrow();

        assertThat(reloaded.getPipelineId()).isEqualTo(set.getPipelineId());
        assertThat(reloaded.getName()).isEqualTo(set.getName());
        assertThat(reloaded.getDescription()).isEqualTo("Adds the audit column");
        assertThat(reloaded.getStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(reloaded.getStatementsChecksum()).isEqualTo(CHECKSUM);
        assertThat(reloaded.getCreatedBy()).isEqualTo(set.getCreatedBy());
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(changeSetRepository.existsByOrganizationIdAndPipelineIdAndName(
                set.getOrganizationId(), set.getPipelineId(), set.getName())).isTrue();
        assertThat(changeSetRepository.findByIdAndOrganizationId(set.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void changeSetNameIsUniquePerPipelineButNotAcrossPipelines() {
        var organizationId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        changeSetRepository.saveAndFlush(newChangeSet(organizationId, pipelineId, "orders-v2"));

        assertThatThrownBy(() -> changeSetRepository.saveAndFlush(newChangeSet(organizationId, pipelineId, "orders-v2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(changeSetRepository.saveAndFlush(newChangeSet(organizationId, UUID.randomUUID(), "orders-v2")).getId())
                .isNotNull();
    }

    @Test
    void statementsRoundTripInOrderAndSequenceIsUniquePerSet() {
        var set = savedChangeSet();
        statementRepository.saveAndFlush(newStatement(set, 1));
        statementRepository.saveAndFlush(newStatement(set, 0));

        var statements = statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(set.getId());

        assertThat(statements).extracting(SchemaChangeSetStatementEntity::getSequenceOrder).containsExactly(0, 1);
        assertThat(statements.getFirst().getQueryType()).isEqualTo(QueryType.DDL);
        assertThat(statements.getFirst().getSqlText()).startsWith("ALTER TABLE");
        assertThat(statements.getFirst().getCreatedAt()).isNotNull();
        assertThatThrownBy(() -> statementRepository.saveAndFlush(newStatement(set, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAChangeSetCascadesStatementsAndPromotions() {
        var set = savedChangeSet();
        statementRepository.saveAndFlush(newStatement(set, 0));
        promotionRepository.saveAndFlush(newPromotion(set, UUID.randomUUID(), SchemaChangePromotionStatus.PENDING));

        jdbcTemplate.update("DELETE FROM schema_change_sets WHERE id = ?", set.getId());

        assertThat(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(set.getId())).isEmpty();
        assertThat(promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(set.getId())).isEmpty();
        assertThat(changeSetRepository.findById(set.getId())).isEmpty();
    }

    @Test
    void bulkStatementDeleteAllowsReinsertingTheSameOrderInOneTransaction() {
        var set = savedChangeSet();
        statementRepository.saveAndFlush(newStatement(set, 0));
        statementRepository.saveAndFlush(newStatement(set, 1));
        var replacement = newStatement(set, 0);
        replacement.setSqlText("ALTER TABLE orders DROP COLUMN legacy_flag");

        // The delete-then-reinsert the authoring service (#879) will do: the bulk delete must run
        // ahead of the new INSERT or the reused sequence_order trips the unique constraint at flush.
        var deleted = new TransactionTemplate(transactionManager).execute(status -> {
            var count = statementRepository.deleteAllByChangeSetId(set.getId());
            statementRepository.save(replacement);
            return count;
        });

        assertThat(deleted).isEqualTo(2);
        assertThat(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(set.getId()))
                .singleElement()
                .satisfies(s -> assertThat(s.getSqlText()).contains("DROP COLUMN"));
    }

    @Test
    void freezeProbeMatchesAnyOfTheGivenStatusesAndListingsBatchLoadStatements() {
        var frozen = savedChangeSet();
        var thawed = savedChangeSet();
        var empty = savedChangeSet();
        promotionRepository.saveAndFlush(newPromotion(frozen, UUID.randomUUID(), SchemaChangePromotionStatus.APPLIED));
        promotionRepository.saveAndFlush(newPromotion(thawed, UUID.randomUUID(), SchemaChangePromotionStatus.FAILED));
        promotionRepository.saveAndFlush(newPromotion(thawed, UUID.randomUUID(), SchemaChangePromotionStatus.CANCELLED));
        statementRepository.saveAndFlush(newStatement(frozen, 1));
        statementRepository.saveAndFlush(newStatement(frozen, 0));
        statementRepository.saveAndFlush(newStatement(thawed, 0));
        var freezing = java.util.EnumSet.of(SchemaChangePromotionStatus.PENDING, SchemaChangePromotionStatus.IN_REVIEW,
                SchemaChangePromotionStatus.APPROVED, SchemaChangePromotionStatus.APPLIED,
                SchemaChangePromotionStatus.PARTIALLY_APPLIED);

        assertThat(promotionRepository.existsByChangeSet_IdAndStatusIn(frozen.getId(), freezing)).isTrue();
        assertThat(promotionRepository.existsByChangeSet_IdAndStatusIn(thawed.getId(), freezing)).isFalse();
        assertThat(promotionRepository.existsByChangeSet_IdAndStatusIn(empty.getId(), freezing)).isFalse();
        assertThat(statementRepository.findAllByChangeSet_IdInOrderBySequenceOrderAsc(
                java.util.List.of(frozen.getId(), thawed.getId(), empty.getId())))
                .extracting(s -> s.getChangeSet().getId(), SchemaChangeSetStatementEntity::getSequenceOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(frozen.getId(), 0),
                        org.assertj.core.groups.Tuple.tuple(frozen.getId(), 1),
                        org.assertj.core.groups.Tuple.tuple(thawed.getId(), 0));
        // The listing specification binds the PG enum column through the criteria API — the shape
        // that avoids "could not determine data type of parameter" on a nullable JPQL parameter.
        var page = changeSetRepository.findAll(
                com.bablsoft.accessflow.schemachange.internal.SchemaChangeSetSpecificationsAccess.forStatus(
                        frozen.getOrganizationId(), SchemaChangeSetStatus.DRAFT),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(SchemaChangeSetEntity::getId).containsExactly(frozen.getId());
    }

    @Test
    void promotionRoundTripsWithEveryColumn() {
        var set = savedChangeSet();
        var promotion = newPromotion(set, UUID.randomUUID(), SchemaChangePromotionStatus.APPLIED);
        var groupId = UUID.randomUUID();
        var appliedAt = Instant.parse("2026-09-22T10:00:00Z");
        promotion.setRequestGroupId(groupId);
        promotion.setAppliedAt(appliedAt);
        promotion.setErrorMessage(null);
        promotion.setSchemaSnapshot("{\"schemas\":[]}");
        promotion.setSnapshotTakenAt(appliedAt);
        promotionRepository.saveAndFlush(promotion);

        var reloaded = promotionRepository.findByRequestGroupId(groupId).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(promotion.getId());
        assertThat(reloaded.getOrganizationId()).isEqualTo(set.getOrganizationId());
        assertThat(reloaded.getEnvironmentId()).isEqualTo(promotion.getEnvironmentId());
        assertThat(reloaded.getDatasourceId()).isEqualTo(promotion.getDatasourceId());
        assertThat(reloaded.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        assertThat(reloaded.getStatementsChecksum()).isEqualTo(CHECKSUM);
        assertThat(reloaded.getPromotedBy()).isEqualTo(promotion.getPromotedBy());
        assertThat(reloaded.getSubmittedAt()).isNotNull();
        assertThat(reloaded.getAppliedAt()).isEqualTo(appliedAt);
        assertThat(reloaded.getSchemaSnapshot()).contains("\"schemas\"");
        assertThat(reloaded.getSnapshotTakenAt()).isEqualTo(appliedAt);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(promotionRepository.findByIdAndOrganizationId(promotion.getId(), set.getOrganizationId())).isPresent();
        assertThat(promotionRepository.findByIdAndOrganizationId(promotion.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void atMostOneNonTerminalPromotionPerChangeSetAndEnvironment() {
        var set = savedChangeSet();
        var environmentId = UUID.randomUUID();
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.PENDING));

        assertThatThrownBy(() -> promotionRepository.saveAndFlush(
                newPromotion(set, environmentId, SchemaChangePromotionStatus.IN_REVIEW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A different environment of the same set is not affected.
        assertThat(promotionRepository.saveAndFlush(
                newPromotion(set, UUID.randomUUID(), SchemaChangePromotionStatus.PENDING)).getId()).isNotNull();
    }

    @Test
    void terminalPromotionsNeverCollide() {
        var set = savedChangeSet();
        var environmentId = UUID.randomUUID();
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.FAILED));
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.APPLIED));
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.CANCELLED));
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.PARTIALLY_APPLIED));
        // …and once every earlier attempt is terminal a fresh open one is allowed again.
        promotionRepository.saveAndFlush(newPromotion(set, environmentId, SchemaChangePromotionStatus.APPROVED));

        assertThat(promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(set.getId())).hasSize(5);
    }

    @Test
    void driftScanAndFindingRoundTripWithEveryColumn() {
        var scan = newScan();
        var finishedAt = Instant.parse("2026-09-22T11:00:00Z");
        scan.setFinishedAt(finishedAt);
        scan.setApplicable(false);
        scan.setFindingsCount(1);
        scan.setPartial(true);
        scan.setErrorMessage("time budget exhausted");
        scanRepository.saveAndFlush(scan);
        var finding = newFinding(scan, "public.orders.total");
        finding.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
        finding.setResolvedAt(finishedAt);
        findingRepository.saveAndFlush(finding);

        var scans = scanRepository.findAllByOrganizationIdAndEnvironmentIdOrderByStartedAtDesc(
                scan.getOrganizationId(), scan.getEnvironmentId());
        var reloaded = findingRepository.findByIdAndOrganizationId(finding.getId(), scan.getOrganizationId()).orElseThrow();

        assertThat(scans).singleElement().satisfies(s -> {
            assertThat(s.getPipelineId()).isEqualTo(scan.getPipelineId());
            assertThat(s.getDatasourceId()).isEqualTo(scan.getDatasourceId());
            assertThat(s.getBaseline()).isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
            assertThat(s.getStartedAt()).isNotNull();
            assertThat(s.getFinishedAt()).isEqualTo(finishedAt);
            assertThat(s.isApplicable()).isFalse();
            assertThat(s.getFindingsCount()).isEqualTo(1);
            assertThat(s.isPartial()).isTrue();
            assertThat(s.getErrorMessage()).isEqualTo("time budget exhausted");
        });
        assertThat(reloaded.getEnvironmentId()).isEqualTo(scan.getEnvironmentId());
        assertThat(reloaded.getObjectPath()).isEqualTo("public.orders.total");
        assertThat(reloaded.getFindingKind()).isEqualTo(SchemaDriftFindingKind.TYPE_MISMATCH);
        assertThat(reloaded.getExpectedValue()).isEqualTo("varchar");
        assertThat(reloaded.getActualValue()).isEqualTo("text");
        assertThat(reloaded.getStatus()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(reloaded.getFirstDetectedAt()).isNotNull();
        assertThat(reloaded.getLastSeenAt()).isNotNull();
        assertThat(reloaded.getResolvedAt()).isEqualTo(finishedAt);
        assertThat(findingRepository.findAllByScan_IdOrderByObjectPathAsc(scan.getId())).hasSize(1);
    }

    private SchemaDriftConfigEntity newDriftConfig(UUID organizationId, UUID pipelineId) {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(organizationId);
        config.setPipelineId(pipelineId);
        return config;
    }

    @Test
    void driftConfigRoundTripsWithEveryColumnAndItsDdlDefaults() {
        var organizationId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var defaults = driftConfigRepository.saveAndFlush(newDriftConfig(organizationId, pipelineId));

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getBaseline()).isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
        assertThat(defaults.getScanIntervalHours()).isEqualTo(24);

        var baselineEnvironmentId = UUID.randomUUID();
        var lastScanAt = Instant.parse("2026-09-22T11:00:00Z");
        defaults.setEnabled(true);
        defaults.setBaseline(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        defaults.setBaselineEnvironmentId(baselineEnvironmentId);
        defaults.setScanIntervalHours(6);
        defaults.setLastScanAt(lastScanAt);
        defaults.setLastScanError("BASELINE_SNAPSHOT_MISSING");
        driftConfigRepository.saveAndFlush(defaults);

        var reloaded = driftConfigRepository.findByPipelineIdAndOrganizationId(pipelineId, organizationId)
                .orElseThrow();
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getBaseline()).isEqualTo(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        assertThat(reloaded.getBaselineEnvironmentId()).isEqualTo(baselineEnvironmentId);
        assertThat(reloaded.getScanIntervalHours()).isEqualTo(6);
        assertThat(reloaded.getLastScanAt()).isEqualTo(lastScanAt);
        assertThat(reloaded.getLastScanError()).isEqualTo("BASELINE_SNAPSHOT_MISSING");
    }

    @Test
    void atMostOneDriftConfigPerPipeline() {
        var pipelineId = UUID.randomUUID();
        driftConfigRepository.saveAndFlush(newDriftConfig(UUID.randomUUID(), pipelineId));

        assertThatThrownBy(() -> driftConfigRepository.saveAndFlush(
                newDriftConfig(UUID.randomUUID(), pipelineId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theJobDrainsOnlyEnabledConfigs() {
        var organizationId = UUID.randomUUID();
        var enabled = newDriftConfig(organizationId, UUID.randomUUID());
        enabled.setEnabled(true);
        driftConfigRepository.saveAndFlush(enabled);
        driftConfigRepository.saveAndFlush(newDriftConfig(organizationId, UUID.randomUUID()));

        assertThat(driftConfigRepository.findAllByEnabledTrue())
                .extracting(SchemaDriftConfigEntity::getId)
                .contains(enabled.getId());
        assertThat(driftConfigRepository.findAllByEnabledTrue())
                .allSatisfy(config -> assertThat(config.isEnabled()).isTrue());
        assertThat(driftConfigRepository.findAllByOrganizationIdOrderByPipelineIdAsc(organizationId))
                .hasSize(2);
    }

    @Test
    void theInFlightProbeIsBoundedByItsHorizon() {
        var scan = newScan();
        scan.setStartedAt(Instant.parse("2026-09-22T10:00:00Z"));
        scanRepository.saveAndFlush(scan);

        assertThat(scanRepository.existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                scan.getOrganizationId(), scan.getEnvironmentId(),
                Instant.parse("2026-09-22T09:30:00Z"))).isTrue();
        // Past the horizon the row is treated as orphaned, so it stops blocking new scans forever.
        assertThat(scanRepository.existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                scan.getOrganizationId(), scan.getEnvironmentId(),
                Instant.parse("2026-09-22T10:30:00Z"))).isFalse();

        scan.setFinishedAt(Instant.parse("2026-09-22T10:05:00Z"));
        scanRepository.saveAndFlush(scan);
        assertThat(scanRepository.existsByOrganizationIdAndEnvironmentIdAndFinishedAtIsNullAndStartedAtAfter(
                scan.getOrganizationId(), scan.getEnvironmentId(),
                Instant.parse("2026-09-22T09:30:00Z"))).isFalse();
    }

    @Test
    void findingsAreLookedUpByTheirNaturalKeyAndActiveStatus() {
        var scan = scanRepository.saveAndFlush(newScan());
        var finding = findingRepository.saveAndFlush(newFinding(scan, "public.orders.total"));

        assertThat(findingRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndObjectPathAndFindingKindOrderByLastSeenAtDesc(
                        scan.getOrganizationId(), scan.getEnvironmentId(), "public.orders.total",
                        SchemaDriftFindingKind.TYPE_MISMATCH))
                .get().extracting(SchemaDriftFindingEntity::getId).isEqualTo(finding.getId());

        assertThat(findingRepository.findAllByOrganizationIdAndEnvironmentIdAndStatusIn(
                scan.getOrganizationId(), scan.getEnvironmentId(),
                java.util.List.of(SchemaDriftFindingStatus.OPEN, SchemaDriftFindingStatus.ACKNOWLEDGED)))
                .hasSize(1);
        assertThat(findingRepository.findAllByOrganizationIdAndEnvironmentIdAndStatusIn(
                scan.getOrganizationId(), scan.getEnvironmentId(),
                java.util.List.of(SchemaDriftFindingStatus.RESOLVED))).isEmpty();
    }

    @Test
    void aFindingCountsAgainstTheScanThatOwnsItAndCarriesAVersion() {
        var scan = scanRepository.saveAndFlush(newScan());
        var finding = findingRepository.saveAndFlush(newFinding(scan, "public.orders.a"));
        findingRepository.saveAndFlush(newFinding(scan, "public.orders.b"));

        assertThat(findingRepository.countByScan_Id(scan.getId())).isEqualTo(2);
        assertThat(finding.getVersion()).isZero();

        finding.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(findingRepository.saveAndFlush(finding).getVersion()).isEqualTo(1);
    }

    @Test
    void aStaleCopyOfAFindingCannotOverwriteANewerOne() {
        var scan = scanRepository.saveAndFlush(newScan());
        var stale = findingRepository.saveAndFlush(newFinding(scan, "public.orders.a"));
        var fresh = findingRepository.findById(stale.getId()).orElseThrow();
        fresh.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
        findingRepository.saveAndFlush(fresh);

        // The scan's copy predates the acknowledgement: V181's version refuses it.
        stale.setLastSeenAt(Instant.parse("2026-09-23T10:00:00Z"));
        assertThatThrownBy(() -> findingRepository.saveAndFlush(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    void theListingsFilterOnTheRealDatabase() {
        // The status predicate compares a PG enum and the pipeline filter joins the owning scan;
        // both only fail at runtime, so they are exercised here rather than with mocks.
        var scan = newScan();
        scanRepository.saveAndFlush(scan);
        var open = findingRepository.saveAndFlush(newFinding(scan, "public.orders.a"));
        var acknowledged = newFinding(scan, "public.orders.b");
        acknowledged.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
        findingRepository.saveAndFlush(acknowledged);
        var org = scan.getOrganizationId();

        var byStatusAndPipeline = driftService.listFindings(org, new SchemaDriftFindingListFilter(
                scan.getPipelineId(), null, SchemaDriftFindingStatus.OPEN), PageRequest.of(0, 20));
        assertThat(byStatusAndPipeline.totalElements()).isEqualTo(1);
        assertThat(byStatusAndPipeline.content().getFirst().id()).isEqualTo(open.getId());

        assertThat(driftService.listFindings(org, new SchemaDriftFindingListFilter(
                UUID.randomUUID(), null, null), PageRequest.of(0, 20)).totalElements()).isZero();
        assertThat(driftService.listScans(org, new SchemaDriftScanListFilter(
                scan.getPipelineId(), scan.getEnvironmentId()), PageRequest.of(0, 20)).totalElements()).isEqualTo(1);
        // Another organization sees nothing: 404-never-403 at the listing level is an empty page.
        assertThat(driftService.listScans(UUID.randomUUID(), SchemaDriftScanListFilter.unfiltered(),
                PageRequest.of(0, 20)).totalElements()).isZero();
    }

    @Test
    void deletingAScanCascadesItsFindings() {
        var scan = scanRepository.saveAndFlush(newScan());
        findingRepository.saveAndFlush(newFinding(scan, "public.orders.b"));
        findingRepository.saveAndFlush(newFinding(scan, "public.orders.a"));

        jdbcTemplate.update("DELETE FROM schema_drift_scans WHERE id = ?", scan.getId());

        assertThat(findingRepository.findAllByScan_IdOrderByObjectPathAsc(scan.getId())).isEmpty();
        assertThat(scanRepository.findById(scan.getId())).isEmpty();
    }
}
