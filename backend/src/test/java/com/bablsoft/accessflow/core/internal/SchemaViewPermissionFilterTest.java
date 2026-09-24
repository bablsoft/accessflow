package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.ForeignKey;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaViewPermissionFilterTest {

    private static Table table(String name, ForeignKey... fks) {
        return new Table(name, List.of(new Column("id", "int", false, true),
                new Column("name", "text", true, false),
                new Column("ssn", "text", true, false)), List.of(fks));
    }

    private static DatabaseSchemaView view() {
        return new DatabaseSchemaView(List.of(
                new Schema("public", List.of(
                        table("customer", new ForeignKey("id", "employee", "id")),
                        table("service", new ForeignKey("id", "customer", "id")),
                        table("salary"),
                        table("employee"))),
                new Schema("HR", List.of(table("payroll"))),
                new Schema("empty", List.of())));
    }

    private static DatasourceUserPermissionView permission(List<String> schemas,
                                                           List<String> tables,
                                                           List<String> denied) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true, false, false, false, schemas, tables, null, denied, null,
                null);
    }

    private static List<String> tableNames(DatabaseSchemaView view) {
        return view.schemas().stream()
                .flatMap(s -> s.tables().stream().map(t -> s.name() + "." + t.name()))
                .toList();
    }

    @Test
    void aTwoTableGrantSeesExactlyThoseTwoTables() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(null, List.of("customer", "service"), null));

        assertThat(tableNames(result)).containsExactly("public.customer", "public.service");
        assertThat(result.schemas()).extracting(Schema::name).containsExactly("public");
    }

    @Test
    void qualifiedAndQuotedTableEntriesMatchCaseInsensitively() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(null, List.of("\"PUBLIC\".\"Salary\"", "hr.PAYROLL"), null));

        assertThat(tableNames(result)).containsExactly("public.salary", "HR.payroll");
    }

    @Test
    void aSchemaGrantSeesEveryTableInThatSchemaAndKeepsAnEmptyAllowedSchema() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(List.of("hr", "empty"), null, null));

        assertThat(tableNames(result)).containsExactly("HR.payroll");
        assertThat(result.schemas()).extracting(Schema::name).containsExactly("HR", "empty");
    }

    @Test
    void anEmptyAllowListIsUnrestricted() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(List.of(" "), List.of(), null));

        assertThat(tableNames(result)).hasSize(5);
        assertThat(result.schemas()).hasSize(3);
        assertThat(result.schemas().getFirst().tables().getFirst().foreignKeys()).hasSize(1);
    }

    @Test
    void foreignKeysToHiddenTablesAreDropped() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(null, List.of("customer", "service"), null));

        var tables = result.schemas().getFirst().tables();
        assertThat(tables.get(0).foreignKeys()).isEmpty();
        assertThat(tables.get(1).foreignKeys()).containsExactly(new ForeignKey("id", "customer", "id"));
    }

    @Test
    void deniedColumnsAndForeignKeysOverThemAreDropped() {
        var withFk = new DatabaseSchemaView(List.of(new Schema("public", List.of(
                new Table("customer", List.of(new Column("id", "int", false, true),
                        new Column("ssn", "text", true, false)),
                        List.of(new ForeignKey("ssn", "person", "ssn"))),
                new Table("orders", List.of(new Column("customer_id", "int", false, false)),
                        List.of(new ForeignKey("customer_id", "customer", "id"),
                                new ForeignKey("customer_id", "person", "ssn")))))));

        var result = SchemaViewPermissionFilter.apply(withFk,
                permission(null, null, List.of("public.customer.ssn", "person.ssn")));

        var tables = result.schemas().getFirst().tables();
        assertThat(tables.get(0).columns()).extracting(Column::name).containsExactly("id");
        assertThat(tables.get(0).foreignKeys()).isEmpty();
        assertThat(tables.get(1).foreignKeys())
                .containsExactly(new ForeignKey("customer_id", "customer", "id"));
    }

    @Test
    void aTableWithoutASchemaMatchesOnlyItsBareName() {
        var schemaless = new DatabaseSchemaView(List.of(new Schema(null, List.of(
                table("customer"), table("salary")))));

        var byTable = SchemaViewPermissionFilter.apply(schemaless,
                permission(List.of("public"), List.of("customer"), null));

        assertThat(byTable.schemas().getFirst().tables()).extracting(Table::name)
                .containsExactly("customer");
    }

    @Test
    void aBareEntryDoesNotRevealASameNamedTableInAnotherSchema() {
        var shared = new DatabaseSchemaView(List.of(
                new Schema("public", List.of(table("customer"), table("orders"))),
                new Schema("hr", List.of(table("customer")))));

        var bare = SchemaViewPermissionFilter.apply(shared,
                permission(null, List.of("customer", "orders"), null));
        var qualified = SchemaViewPermissionFilter.apply(shared,
                permission(null, List.of("public.customer"), null));

        assertThat(tableNames(bare)).containsExactly("public.orders");
        assertThat(tableNames(qualified)).containsExactly("public.customer");
    }

    @Test
    void aCatalogQualifiedEntryCoversTheTrailingSchemaAndTable() {
        var result = SchemaViewPermissionFilter.apply(view(),
                permission(null, List.of("proj.public.salary", "other.hr.payroll.x"), null));

        assertThat(tableNames(result)).containsExactly("public.salary");
    }

    @Test
    void aForeignKeyWhoseTargetNameAlsoBelongsToAHiddenTableIsDropped() {
        var shared = new DatabaseSchemaView(List.of(
                new Schema("public", List.of(
                        table("orders", new ForeignKey("id", "customer", "id")),
                        table("customer"))),
                new Schema("hr", List.of(table("customer")))));

        var result = SchemaViewPermissionFilter.apply(shared,
                permission(null, List.of("public.orders", "public.customer"), null));

        assertThat(tableNames(result)).containsExactly("public.orders", "public.customer");
        assertThat(result.schemas().getFirst().tables().getFirst().foreignKeys()).isEmpty();
    }

    @Test
    void nullViewsAndNullListsAreTolerated() {
        assertThat(SchemaViewPermissionFilter.apply(null, permission(null, null, null))).isNull();
        var nullSchemas = new DatabaseSchemaView(null);
        assertThat(SchemaViewPermissionFilter.apply(nullSchemas, permission(null, null, null)))
                .isSameAs(nullSchemas);
        var nullLists = new DatabaseSchemaView(List.of(new Schema("public", null),
                new Schema("x", List.of(new Table("t", null, null), new Table(" ", null, null)))));

        var result = SchemaViewPermissionFilter.apply(nullLists,
                permission(List.of("x"), null, null));

        assertThat(result.schemas()).extracting(Schema::name).containsExactly("x");
        assertThat(result.schemas().getFirst().tables()).extracting(Table::name).containsExactly("t");
    }
}
