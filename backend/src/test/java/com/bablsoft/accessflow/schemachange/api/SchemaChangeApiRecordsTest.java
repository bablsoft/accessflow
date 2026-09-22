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
    void listFilterNoneMatchesEverything() {
        var filter = SchemaChangeSetListFilter.none();

        assertThat(filter.pipelineId()).isNull();
        assertThat(filter.status()).isNull();
    }
}
