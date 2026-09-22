package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, statements);
        statements.clear();

        assertThat(view.statements()).hasSize(1);
        assertThatThrownBy(() -> view.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeSetViewTreatsNullStatementsAsEmpty() {
        var view = new SchemaChangeSetView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n", null,
                SchemaChangeSetStatus.DRAFT, null, null, Instant.EPOCH, Instant.EPOCH, null);

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
        var promotion = new SchemaChangePromotionView(id, orgId, refId, refId, refId, refId,
                SchemaChangePromotionStatus.APPLIED, "a".repeat(64), refId, at, at, "boom", "{}", at);
        var scan = new SchemaDriftScanView(id, orgId, refId, refId, refId, SchemaDriftBaseline.PROMOTION_SNAPSHOT,
                at, at, false, 2, true, "budget");
        var finding = new SchemaDriftFindingView(id, orgId, refId, refId, "public.t.c",
                SchemaDriftFindingKind.MISSING_IN_TARGET, "int", null, SchemaDriftFindingStatus.RESOLVED, at, at, at);
        var set = new SchemaChangeSetView(id, orgId, refId, "n", "d", SchemaChangeSetStatus.ARCHIVED, "b".repeat(64),
                refId, at, at, List.of(statement));

        assertThat(statement).extracting("id", "sequenceOrder", "sqlText", "queryType", "createdAt")
                .containsExactly(id, 3, "CREATE TABLE t (id INT)", QueryType.DDL, at);
        assertThat(promotion).extracting("id", "organizationId", "changeSetId", "environmentId", "datasourceId",
                        "requestGroupId", "status", "statementsChecksum", "promotedBy", "submittedAt", "appliedAt",
                        "errorMessage", "schemaSnapshot", "snapshotTakenAt")
                .containsExactly(id, orgId, refId, refId, refId, refId, SchemaChangePromotionStatus.APPLIED,
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
        var input = new SchemaChangeSetStatementInput("ALTER TABLE t ADD c INT");
        var filter = new SchemaChangeSetListFilter(environmentId, SchemaChangeSetStatus.DRAFT);

        assertThat(update.name()).isEqualTo("n");
        assertThat(update.description()).isEqualTo("d");
        assertThat(update.status()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(promote.environmentId()).isEqualTo(environmentId);
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
}
