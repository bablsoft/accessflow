package com.bablsoft.accessflow.schemachange;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
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
 * Validates that V178 applies and every schemachange JPA entity maps to its table — entity ↔ DDL
 * parity under {@code ddl-auto=validate}, including the five new pg enum {@code columnDefinition}s
 * plus the shared {@code query_type}, the {@code char(64)} checksums and the jsonb snapshot, both
 * {@code ON DELETE CASCADE}s, the two plain unique constraints and the partial unique index that
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
    void derivedStatementDeleteClearsTheSet() {
        var set = savedChangeSet();
        statementRepository.saveAndFlush(newStatement(set, 0));

        // A derived delete needs a transaction; the caller supplies it in production too.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> statementRepository.deleteAllByChangeSet_Id(set.getId()));

        assertThat(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(set.getId())).isEmpty();
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
