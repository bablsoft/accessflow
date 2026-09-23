package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the compact constructors of the change-set view and command records. */
class SchemaChangeApiRecordsTest {

    @Test
    void changeSetViewCopiesStatementsDefensively() {
        var statements = new ArrayList<SchemaChangeSetStatementView>();
        statements.add(new SchemaChangeSetStatementView(UUID.randomUUID(), 0, "CREATE TABLE t (id INT)",
                QueryType.DDL, Instant.EPOCH));

        var view = new SchemaChangeSetView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n", null,
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, statements, null);
        statements.clear();

        assertThat(view.statements()).hasSize(1);
        assertThatThrownBy(() -> view.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeSetViewCopiesReviewWarningsAndTreatsNullAsEmpty() {
        var finding = new SchemaChangeStatementFinding(2, UUID.randomUUID(),
                new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, 1, Map.of("statement_type", "DROP")));
        var warnings = new ArrayList<>(List.of(finding));

        var view = new SchemaChangeSetView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n", null,
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, null, warnings);
        warnings.clear();

        assertThat(view.reviewWarnings()).containsExactly(finding);
        assertThatThrownBy(() -> view.reviewWarnings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SchemaChangeSetView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n", null,
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, null, null).reviewWarnings())
                .isEmpty();
    }

    @Test
    void statementFindingDelegatesBlockingAndRejectsNulls() {
        var datasourceId = UUID.randomUUID();
        var block = new SqlReviewFinding("drop_statement", SqlReviewSeverity.BLOCK, 0, null, Map.of());
        var warn = new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, null, Map.of());

        assertThat(new SchemaChangeStatementFinding(0, datasourceId, block).isBlocking()).isTrue();
        assertThat(new SchemaChangeStatementFinding(1, datasourceId, warn).isBlocking()).isFalse();
        assertThat(new SchemaChangeStatementFinding(1, datasourceId, warn))
                .extracting("statementIndex", "datasourceId", "finding").containsExactly(1, datasourceId, warn);
        assertThatThrownBy(() -> new SchemaChangeStatementFinding(0, null, warn)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchemaChangeStatementFinding(0, datasourceId, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void changeSetViewTreatsNullStatementsAsEmpty() {
        var view = new SchemaChangeSetView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n", null,
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, null, null);

        assertThat(view.statements()).isEmpty();
    }

    @Test
    void createCommandCopiesStatementsAndTreatsNullAsEmpty() {
        var inputs = new ArrayList<>(List.of(new SchemaChangeSetStatementInput("CREATE TABLE t (id INT)")));

        var command = new CreateSchemaChangeSetCommand(UUID.randomUUID(), "n", "d", inputs);
        inputs.clear();

        assertThat(command.statements()).hasSize(1);
        assertThatThrownBy(() -> command.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new CreateSchemaChangeSetCommand(UUID.randomUUID(), "n", null, null).statements()).isEmpty();
    }

    @Test
    void viewRecordsRoundTripEveryComponent() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var refId = UUID.randomUUID();
        var at = Instant.parse("2026-09-22T10:00:00Z");

        var statement = new SchemaChangeSetStatementView(id, 3, "CREATE TABLE t (id INT)", QueryType.DDL, at);
        var promotion = new SchemaChangePromotionView(id, orgId, refId, refId, "staging", refId, refId,
                SchemaChangePromotionStatus.APPLIED, "a".repeat(64), refId, at, at, "boom", "{}", at);
        var scan = new SchemaDriftScanView(id, orgId, refId, refId, refId, SchemaDriftBaseline.PROMOTION_SNAPSHOT,
                at, at, false, 2, true, "budget");
        var finding = new SchemaDriftFindingView(id, orgId, refId, refId, "public.t.c",
                SchemaDriftFindingKind.MISSING_IN_TARGET, "int", null, SchemaDriftFindingStatus.RESOLVED, at, at, at);
        var set = new SchemaChangeSetView(id, orgId, refId, "n", "d", SchemaChangeSetStatus.ARCHIVED, "b".repeat(64),
                refId, at, at, List.of(statement), null);

        assertThat(statement).extracting("id", "sequenceOrder", "sqlText", "queryType", "createdAt")
                .containsExactly(id, 3, "CREATE TABLE t (id INT)", QueryType.DDL, at);
        assertThat(promotion).extracting("id", "organizationId", "changeSetId", "environmentId", "environmentName",
                        "datasourceId", "requestGroupId", "status", "statementsChecksum", "promotedBy", "submittedAt",
                        "appliedAt", "errorMessage", "schemaSnapshot", "snapshotTakenAt")
                .containsExactly(id, orgId, refId, refId, "staging", refId, refId, SchemaChangePromotionStatus.APPLIED,
                        "a".repeat(64), refId, at, at, "boom", "{}", at);
        assertThat(scan).extracting("id", "organizationId", "pipelineId", "environmentId", "datasourceId", "baseline",
                        "startedAt", "finishedAt", "applicable", "findingsCount", "partial", "errorMessage")
                .containsExactly(id, orgId, refId, refId, refId, SchemaDriftBaseline.PROMOTION_SNAPSHOT, at, at, false,
                        2, true, "budget");
        assertThat(finding).extracting("id", "organizationId", "scanId", "environmentId", "objectPath", "findingKind",
                        "expectedValue", "actualValue", "status", "firstDetectedAt", "lastSeenAt", "resolvedAt")
                .containsExactly(id, orgId, refId, refId, "public.t.c", SchemaDriftFindingKind.MISSING_IN_TARGET,
                        "int", null, SchemaDriftFindingStatus.RESOLVED, at, at, at);
        assertThat(set).extracting("id", "organizationId", "pipelineId", "name", "description", "status",
                        "statementsChecksum", "createdBy", "createdAt", "updatedAt")
                .containsExactly(id, orgId, refId, "n", "d", SchemaChangeSetStatus.ARCHIVED, "b".repeat(64), refId,
                        at, at);
    }

    @Test
    void commandRecordsRoundTripEveryComponent() {
        var environmentId = UUID.randomUUID();

        var update = new UpdateSchemaChangeSetCommand("n", "d", SchemaChangeSetStatus.ACTIVE);
        var promote = new PromoteSchemaChangeSetCommand(environmentId);
        var promoteWithProvenance = new PromoteSchemaChangeSetCommand(environmentId, "10.0.0.1", "curl/8");
        var input = new SchemaChangeSetStatementInput("ALTER TABLE t ADD c INT");
        var filter = new SchemaChangeSetListFilter(environmentId, SchemaChangeSetStatus.DRAFT);

        assertThat(update.name()).isEqualTo("n");
        assertThat(update.description()).isEqualTo("d");
        assertThat(update.status()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(promote.environmentId()).isEqualTo(environmentId);
        assertThat(promote.submittedIp()).isNull();
        assertThat(promote.submittedUserAgent()).isNull();
        assertThat(promoteWithProvenance).extracting("environmentId", "submittedIp", "submittedUserAgent")
                .containsExactly(environmentId, "10.0.0.1", "curl/8");
        assertThat(input.sqlText()).startsWith("ALTER TABLE");
        assertThat(filter.pipelineId()).isEqualTo(environmentId);
        assertThat(filter.status()).isEqualTo(SchemaChangeSetStatus.DRAFT);
    }

    @Test
    void listFilterNoneMatchesEverything() {
        var filter = SchemaChangeSetListFilter.none();

        assertThat(filter.pipelineId()).isNull();
        assertThat(filter.status()).isNull();
    }

    @Test
    void theDriftListFiltersCarryTheirFieldsAndHaveAnUnfilteredForm() {
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();

        var scans = new SchemaDriftScanListFilter(pipelineId, environmentId);
        assertThat(scans.pipelineId()).isEqualTo(pipelineId);
        assertThat(scans.environmentId()).isEqualTo(environmentId);
        assertThat(SchemaDriftScanListFilter.unfiltered())
                .extracting("pipelineId", "environmentId").containsOnlyNulls();

        var findings = new SchemaDriftFindingListFilter(pipelineId, environmentId,
                SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(findings.status()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
        assertThat(SchemaDriftFindingListFilter.unfiltered())
                .extracting("pipelineId", "environmentId", "status").containsOnlyNulls();
    }

    @Test
    void theDriftConfigRecordsCarryTheirFields() {
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var now = Instant.parse("2026-09-22T10:00:00Z");

        var view = new SchemaDriftConfigView(UUID.randomUUID(), UUID.randomUUID(), pipelineId, true,
                SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId, 6, now, "REASON");
        assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        assertThat(view.baselineEnvironmentId()).isEqualTo(environmentId);
        assertThat(view.scanIntervalHours()).isEqualTo(6);
        assertThat(view.lastScanAt()).isEqualTo(now);
        assertThat(view.lastScanError()).isEqualTo("REASON");

        var command = new UpsertSchemaDriftConfigCommand(false, SchemaDriftBaseline.PROMOTION_SNAPSHOT,
                null, 24);
        assertThat(command.enabled()).isFalse();
        assertThat(command.baseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(command.baselineEnvironmentId()).isNull();
        assertThat(command.scanIntervalHours()).isEqualTo(24);
    }

    @Test
    void theDriftEnumsCoverEveryPgEnumValue() {
        assertThat(SchemaDriftBaseline.values()).containsExactly(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT,
                SchemaDriftBaseline.BASELINE_ENVIRONMENT, SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(SchemaDriftFindingStatus.values()).containsExactly(SchemaDriftFindingStatus.OPEN,
                SchemaDriftFindingStatus.ACKNOWLEDGED, SchemaDriftFindingStatus.RESOLVED);
        assertThat(SchemaDriftFindingKind.values()).containsExactly(
                SchemaDriftFindingKind.MISSING_IN_TARGET, SchemaDriftFindingKind.UNEXPECTED_IN_TARGET,
                SchemaDriftFindingKind.TYPE_MISMATCH, SchemaDriftFindingKind.NULLABILITY_MISMATCH,
                SchemaDriftFindingKind.PRIMARY_KEY_MISMATCH, SchemaDriftFindingKind.FOREIGN_KEY_MISMATCH);
    }
}
