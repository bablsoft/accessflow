package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaHasherTest {

    private final SchemaHasher hasher = new SchemaHasher();

    private DatabaseSchemaView schema(List<Schema> schemas) {
        return new DatabaseSchemaView(schemas);
    }

    private Schema publicSchema(List<Table> tables) {
        return new Schema("public", tables);
    }

    private Table usersTable() {
        return new Table("users", List.of(
                new Column("id", "int4", false, true),
                new Column("email", "varchar", true, false)), List.of());
    }

    private Table ordersTable() {
        return new Table("orders", List.of(
                new Column("id", "int4", false, true)), List.of());
    }

    @Test
    void producesStableSixtyFourCharHex() {
        var hash = hasher.hash(schema(List.of(publicSchema(List.of(usersTable())))));

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isOrderIndependentAcrossTablesAndColumns() {
        var a = schema(List.of(publicSchema(List.of(usersTable(), ordersTable()))));
        var b = schema(List.of(publicSchema(List.of(ordersTable(), usersTable()))));

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void differsWhenColumnsDiffer() {
        var a = schema(List.of(publicSchema(List.of(usersTable()))));
        var b = schema(List.of(publicSchema(List.of(new Table("users",
                List.of(new Column("id", "int4", false, true)), List.of())))));

        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }

    @Test
    void nullAndEmptySchemaHashToSameStableValue() {
        var emptyHash = hasher.hash(schema(List.of()));
        var nullHash = hasher.hash(new DatabaseSchemaView(null));

        assertThat(emptyHash).hasSize(64);
        assertThat(nullHash).isEqualTo(emptyHash);
    }
}
