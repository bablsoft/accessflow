package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSchemaFingerprintServiceTest {

    private final DefaultSchemaFingerprintService service = new DefaultSchemaFingerprintService();

    private static DatabaseSchemaView.Column column(String name, String type, boolean nullable,
                                                    boolean primaryKey) {
        return new DatabaseSchemaView.Column(name, type, nullable, primaryKey);
    }

    private static DatabaseSchemaView view(List<DatabaseSchemaView.Column> columns,
                                           List<DatabaseSchemaView.ForeignKey> foreignKeys) {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", columns, foreignKeys)))));
    }

    private static DatabaseSchemaView view(List<DatabaseSchemaView.Column> columns) {
        return view(columns, List.of());
    }

    private static final DatabaseSchemaView.Column ID = column("id", "uuid", false, true);

    @Test
    void producesStableSixtyFourCharHex() {
        assertThat(service.fingerprint(view(List.of(ID)))).matches("[0-9a-f]{64}");
    }

    @Test
    void isOrderIndependentAcrossSchemasTablesColumnsAndForeignKeys() {
        var a = column("a", "text", true, false);
        var b = column("b", "int4", true, false);
        var fk1 = new DatabaseSchemaView.ForeignKey("a", "customers", "id");
        var fk2 = new DatabaseSchemaView.ForeignKey("b", "regions", "id");

        assertThat(service.fingerprint(view(List.of(a, b), List.of(fk1, fk2))))
                .isEqualTo(service.fingerprint(view(List.of(b, a), List.of(fk2, fk1))));
    }

    @Test
    void isOrderIndependentAcrossTables() {
        var t1 = new DatabaseSchemaView.Table("orders", List.of(ID), List.of());
        var t2 = new DatabaseSchemaView.Table("customers", List.of(ID), List.of());
        var forward = new DatabaseSchemaView(
                List.of(new DatabaseSchemaView.Schema("public", List.of(t1, t2))));
        var reversed = new DatabaseSchemaView(
                List.of(new DatabaseSchemaView.Schema("public", List.of(t2, t1))));

        assertThat(service.fingerprint(forward)).isEqualTo(service.fingerprint(reversed));
    }

    @Test
    void differsWhenColumnsDiffer() {
        assertThat(service.fingerprint(view(List.of(ID))))
                .isNotEqualTo(service.fingerprint(view(List.of(ID, column("name", "text", true, false)))));
    }

    @Test
    void differsWhenTypeDiffers() {
        assertThat(service.fingerprint(view(List.of(column("id", "uuid", false, true)))))
                .isNotEqualTo(service.fingerprint(view(List.of(column("id", "text", false, true)))));
    }

    // The three regressions the predecessor SchemaHasher could not catch: it canonicalized only
    // name:type, so each of these hashed identically to its counterpart (#881).

    @Test
    void differsWhenNullabilityFlips() {
        assertThat(service.fingerprint(view(List.of(column("email", "text", false, false)))))
                .isNotEqualTo(service.fingerprint(view(List.of(column("email", "text", true, false)))));
    }

    @Test
    void differsWhenPrimaryKeyChanges() {
        assertThat(service.fingerprint(view(List.of(column("id", "uuid", false, true)))))
                .isNotEqualTo(service.fingerprint(view(List.of(column("id", "uuid", false, false)))));
    }

    @Test
    void differsWhenAForeignKeyIsAdded() {
        assertThat(service.fingerprint(view(List.of(ID), List.of())))
                .isNotEqualTo(service.fingerprint(view(List.of(ID),
                        List.of(new DatabaseSchemaView.ForeignKey("id", "customers", "id")))));
    }

    @Test
    void differsWhenAForeignKeyIsRetargeted() {
        var toCustomers = List.of(new DatabaseSchemaView.ForeignKey("id", "customers", "id"));
        var toRegions = List.of(new DatabaseSchemaView.ForeignKey("id", "regions", "id"));

        assertThat(service.fingerprint(view(List.of(ID), toCustomers)))
                .isNotEqualTo(service.fingerprint(view(List.of(ID), toRegions)));
    }

    @Test
    void escapingKeepsDelimiterBearingNamesDistinct() {
        // Without escaping both render as the token "a:b:c" and collide.
        assertThat(service.fingerprint(view(List.of(column("a:b", "c", true, false)))))
                .isNotEqualTo(service.fingerprint(view(List.of(column("a", "b:c", true, false)))));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(service.fingerprint(view(List.of(column("ID", "UUID", false, true)))))
                .isEqualTo(service.fingerprint(view(List.of(column("id", "uuid", false, true)))));
    }

    @Test
    void nullAndEmptySchemaHashToTheSameStableValue() {
        var empty = service.fingerprint(new DatabaseSchemaView(List.of()));

        assertThat(service.fingerprint(null)).isEqualTo(empty);
        assertThat(service.fingerprint(new DatabaseSchemaView(null))).isEqualTo(empty);
        assertThat(empty).matches("[0-9a-f]{64}");
    }

    @Test
    void toleratesNullMembersAndNullNames() {
        var view = new DatabaseSchemaView(Arrays.asList(
                (DatabaseSchemaView.Schema) null,
                new DatabaseSchemaView.Schema(null, Arrays.asList(
                        (DatabaseSchemaView.Table) null,
                        new DatabaseSchemaView.Table(null,
                                Arrays.asList((DatabaseSchemaView.Column) null,
                                        column(null, null, false, false)),
                                Arrays.asList((DatabaseSchemaView.ForeignKey) null))))));

        assertThat(service.fingerprint(view)).matches("[0-9a-f]{64}");
    }

    @Test
    void toleratesNullTableAndColumnLists() {
        var view = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", null)));
        var withNullColumns = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", null, null)))));

        assertThat(service.fingerprint(view)).matches("[0-9a-f]{64}");
        assertThat(service.fingerprint(withNullColumns)).matches("[0-9a-f]{64}");
    }
}
