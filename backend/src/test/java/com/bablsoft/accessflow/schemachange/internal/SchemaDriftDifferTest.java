package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftDifferTest {

    private static final SchemaDriftDiffer.DiffLimits GENEROUS = new SchemaDriftDiffer.DiffLimits(500, 500);

    private static DatabaseSchemaView.Column column(String name, String type, boolean nullable, boolean pk) {
        return new DatabaseSchemaView.Column(name, type, nullable, pk);
    }

    private static DatabaseSchemaView.Table table(String name, List<DatabaseSchemaView.Column> columns) {
        return new DatabaseSchemaView.Table(name, columns, List.of());
    }

    private static DatabaseSchemaView view(List<DatabaseSchemaView.Table> tables) {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", tables)));
    }

    private static DatabaseSchemaView oneTable(List<DatabaseSchemaView.Column> columns) {
        return view(List.of(table("orders", columns)));
    }

    private static SchemaDriftDiffer.DiffResult diff(DatabaseSchemaView baseline, DatabaseSchemaView target) {
        return SchemaDriftDiffer.diff(baseline, target, GENEROUS, () -> false);
    }

    private static final DatabaseSchemaView.Column ID = column("id", "uuid", false, true);

    // --- one case per finding kind ------------------------------------------------------------

    @Test
    void reportsATableMissingInTheTarget() {
        var baseline = view(List.of(table("orders", List.of(ID)), table("customers", List.of(ID))));
        var target = view(List.of(table("orders", List.of(ID))));

        assertThat(diff(baseline, target).findings())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.objectPath()).isEqualTo("public.customers");
                    assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.MISSING_IN_TARGET);
                    assertThat(f.actualValue()).isNull();
                });
    }

    @Test
    void reportsATableOnlyInTheTarget() {
        var baseline = view(List.of(table("orders", List.of(ID))));
        var target = view(List.of(table("orders", List.of(ID)), table("audit_tmp", List.of(ID))));

        assertThat(diff(baseline, target).findings())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.objectPath()).isEqualTo("public.audit_tmp");
                    assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.UNEXPECTED_IN_TARGET);
                    assertThat(f.expectedValue()).isNull();
                });
    }

    @Test
    void reportsAMissingAndAnUnexpectedColumn() {
        var baseline = oneTable(List.of(ID, column("email", "text", true, false)));
        var target = oneTable(List.of(ID, column("phone", "text", true, false)));

        assertThat(diff(baseline, target).findings())
                .extracting(SchemaDriftDiffer.DriftFinding::objectPath, SchemaDriftDiffer.DriftFinding::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("public.orders.email",
                                SchemaDriftFindingKind.MISSING_IN_TARGET),
                        org.assertj.core.groups.Tuple.tuple("public.orders.phone",
                                SchemaDriftFindingKind.UNEXPECTED_IN_TARGET));
    }

    @Test
    void reportsATypeMismatch() {
        var findings = diff(oneTable(List.of(column("total", "numeric", true, false))),
                oneTable(List.of(column("total", "float8", true, false)))).findings();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.TYPE_MISMATCH);
            assertThat(f.expectedValue()).isEqualTo("numeric");
            assertThat(f.actualValue()).isEqualTo("float8");
        });
    }

    @Test
    void doesNotReportATypeMismatchOnCaseAlone() {
        assertThat(diff(oneTable(List.of(column("total", "NUMERIC", true, false))),
                oneTable(List.of(column("total", "numeric", true, false)))).findings()).isEmpty();
    }

    @Test
    void reportsANullabilityFlip() {
        var findings = diff(oneTable(List.of(column("email", "text", false, false))),
                oneTable(List.of(column("email", "text", true, false)))).findings();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.NULLABILITY_MISMATCH);
            assertThat(f.expectedValue()).isEqualTo("NOT NULL");
            assertThat(f.actualValue()).isEqualTo("NULL");
        });
    }

    @Test
    void reportsAPrimaryKeyChange() {
        var findings = diff(oneTable(List.of(column("id", "uuid", false, true))),
                oneTable(List.of(column("id", "uuid", false, false)))).findings();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.PRIMARY_KEY_MISMATCH);
            assertThat(f.expectedValue()).isEqualTo("PRIMARY KEY");
            assertThat(f.actualValue()).isEqualTo("NOT PRIMARY KEY");
        });
    }

    @Test
    void reportsAForeignKeyChangeAsOneFindingOnTheOwningColumn() {
        var baseline = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(ID),
                        List.of(new DatabaseSchemaView.ForeignKey("customer_id", "customers", "id")))))));
        var target = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(ID),
                        List.of(new DatabaseSchemaView.ForeignKey("customer_id", "accounts", "id")))))));

        // Retargeting is one finding, not a missing/unexpected pair.
        assertThat(diff(baseline, target).findings()).singleElement().satisfies(f -> {
            assertThat(f.objectPath()).isEqualTo("public.orders.customer_id");
            assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.FOREIGN_KEY_MISMATCH);
            assertThat(f.expectedValue()).isEqualTo("customers.id");
            assertThat(f.actualValue()).isEqualTo("accounts.id");
        });
    }

    @Test
    void reportsADroppedForeignKeyAsNone() {
        var baseline = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(ID),
                                List.of(new DatabaseSchemaView.ForeignKey("customer_id", "customers", "id"))),
                        new DatabaseSchemaView.Table("invoices", List.of(ID),
                                List.of(new DatabaseSchemaView.ForeignKey("order_id", "orders", "id")))))));
        var target = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(ID), List.of()),
                        new DatabaseSchemaView.Table("invoices", List.of(ID),
                                List.of(new DatabaseSchemaView.ForeignKey("order_id", "orders", "id")))))));

        // One table losing its keys is real drift: the suppression gate only trips when a whole side
        // reports none.
        assertThat(diff(baseline, target).findings()).singleElement().satisfies(f -> {
            assertThat(f.objectPath()).isEqualTo("public.orders.customer_id");
            assertThat(f.expectedValue()).isEqualTo("customers.id");
            assertThat(f.actualValue()).isEqualTo("(none)");
        });
    }

    @Test
    void suppressesForeignKeysWhenOneWholeSideReportsNone() {
        var baseline = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(ID),
                        List.of(new DatabaseSchemaView.ForeignKey("customer_id", "customers", "id")))))));
        var target = view(List.of(table("orders", List.of(ID))));

        var result = diff(baseline, target);

        // The JDBC introspector swallows a failed foreign-key read and returns an empty list, so a
        // whole side reporting none is a read failure far more often than a real schema.
        assertThat(result.foreignKeysSuppressed()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void doesNotSuppressWhenNeitherSideReportsForeignKeys() {
        var result = diff(oneTable(List.of(ID)), oneTable(List.of(ID)));

        assertThat(result.foreignKeysSuppressed()).isFalse();
    }

    // --- noise control ------------------------------------------------------------------------

    @Test
    void aMissingSchemaIsOneFindingAndIsNotDescendedInto() {
        var baseline = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(table("orders", List.of(ID)))),
                new DatabaseSchemaView.Schema("reporting", List.of(
                        table("daily", List.of(ID, column("total", "numeric", true, false))),
                        table("weekly", List.of(ID))))));
        var target = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(table("orders", List.of(ID))))));

        var result = diff(baseline, target);

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.objectPath()).isEqualTo("reporting");
            assertThat(f.kind()).isEqualTo(SchemaDriftFindingKind.MISSING_IN_TARGET);
            assertThat(f.expectedValue()).isEqualTo("reporting (2 tables)");
        });
        // Its tables never enter the walk, so they cannot consume the table cap either.
        assertThat(result.reachedTableKeys()).containsExactly("public.orders");
    }

    @Test
    void aMissingTableIsOneFindingAndIsNotDescendedInto() {
        var wide = IntStream.range(0, 40)
                .mapToObj(i -> column("c" + i, "text", true, false))
                .toList();
        var baseline = view(List.of(table("orders", List.of(ID)), table("archive", wide)));
        var target = view(List.of(table("orders", List.of(ID))));

        assertThat(diff(baseline, target).findings()).singleElement().satisfies(f -> {
            assertThat(f.objectPath()).isEqualTo("public.archive");
            assertThat(f.expectedValue()).isEqualTo("archive (40 columns)");
        });
    }

    @Test
    void oneColumnCanCarryTwoFindingsOfDifferentKinds() {
        var findings = diff(oneTable(List.of(column("email", "text", false, false))),
                oneTable(List.of(column("email", "varchar", true, false)))).findings();

        assertThat(findings)
                .hasSize(2)
                .allSatisfy(f -> assertThat(f.objectPath()).isEqualTo("public.orders.email"))
                .extracting(SchemaDriftDiffer.DriftFinding::kind)
                .containsExactly(SchemaDriftFindingKind.TYPE_MISMATCH,
                        SchemaDriftFindingKind.NULLABILITY_MISMATCH);
    }

    @Test
    void emissionOrderIsDeterministic() {
        var baseline = view(List.of(table("zebra", List.of(ID)), table("alpha", List.of(ID))));
        var target = view(List.of());

        assertThat(diff(baseline, target).findings())
                .extracting(SchemaDriftDiffer.DriftFinding::objectPath)
                .containsExactly("public.alpha", "public.zebra");
    }

    // --- caps and the budget ------------------------------------------------------------------

    @Test
    void theTableCapAppliesToTheUnionAndFlagsPartial() {
        var baselineTables = new ArrayList<DatabaseSchemaView.Table>();
        var targetTables = new ArrayList<DatabaseSchemaView.Table>();
        for (var i = 0; i < 3; i++) {
            baselineTables.add(table("b" + i, List.of(ID)));
            targetTables.add(table("t" + i, List.of(ID)));
        }
        var result = SchemaDriftDiffer.diff(view(baselineTables), view(targetTables),
                new SchemaDriftDiffer.DiffLimits(2, 500), () -> false);

        // Six distinct tables, capped at the first two of the sorted union.
        assertThat(result.partial()).isTrue();
        assertThat(result.reachedTableKeys()).containsExactlyInAnyOrder("public.b0", "public.b1");
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void theFindingsCapFlagsPartialAndExcludesTheAbandonedTable() {
        var baseline = view(List.of(
                table("a", List.of(column("c1", "text", true, false), column("c2", "text", true, false))),
                table("b", List.of(column("c1", "text", true, false)))));
        var target = view(List.of(table("a", List.of()), table("b", List.of())));

        var result = SchemaDriftDiffer.diff(baseline, target,
                new SchemaDriftDiffer.DiffLimits(500, 1), () -> false);

        assertThat(result.partial()).isTrue();
        assertThat(result.findings()).hasSize(1);
        // The table the cap cut short is not "reached", so its findings can never be resolved by
        // this scan.
        assertThat(result.reachedTableKeys()).doesNotContain("public.a");
    }

    @Test
    void theTimeBudgetFlagsPartialAndStopsTheWalk() {
        var baseline = view(List.of(table("a", List.of(ID)), table("b", List.of(ID)), table("c", List.of(ID))));
        var target = view(List.of());
        var calls = new int[]{0};

        // Out of time once the first table has been compared.
        var result = SchemaDriftDiffer.diff(baseline, target, GENEROUS, () -> calls[0]++ >= 1);

        assertThat(result.partial()).isTrue();
        assertThat(result.reachedTableKeys()).containsExactly("public.a");
        assertThat(result.findings()).hasSize(1);
    }

    // --- null tolerance -----------------------------------------------------------------------

    @Test
    void toleratesNullViewsAndNullLists() {
        assertThat(diff(null, null).findings()).isEmpty();
        assertThat(diff(new DatabaseSchemaView(null), new DatabaseSchemaView(null)).findings()).isEmpty();

        var nullMembers = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", null)));
        assertThat(diff(nullMembers, nullMembers).findings()).isEmpty();

        var nullColumns = view(List.of(new DatabaseSchemaView.Table("orders", null, null)));
        assertThat(diff(nullColumns, nullColumns).findings()).isEmpty();
    }

    @Test
    void identicalSchemasProduceNoFindings() {
        var schema = oneTable(List.of(ID, column("email", "text", true, false)));

        var result = diff(schema, schema);

        assertThat(result.findings()).isEmpty();
        assertThat(result.partial()).isFalse();
        assertThat(result.schemaLevelComplete()).isTrue();
        assertThat(result.reachedTableKeys()).containsExactly("public.orders");
    }

    @Test
    void knownTableKeysCoverTheWholeUnionEvenWhenTheCapCutsTheWalk() {
        var baseline = view(List.of(table("a", List.of(ID)), table("b", List.of(ID)), table("c", List.of(ID))));
        var target = view(List.of());

        var result = SchemaDriftDiffer.diff(baseline, target, new SchemaDriftDiffer.DiffLimits(1, 500), () -> false);

        // Reconciliation maps a stored path to its table by these, so a table the cap skipped must
        // still be known — otherwise its findings would look tableless and resolve.
        assertThat(result.knownTableKeys()).containsExactlyInAnyOrder("public.a", "public.b", "public.c");
        assertThat(result.reachedTableKeys()).containsExactly("public.a");
    }

    @Test
    void dottedColumnNamesKeepTheirRealTableKey() {
        // Elasticsearch flattens nested fields to dot-paths.
        var baseline = oneTable(List.of(column("customer.id", "keyword", true, false)));
        var target = oneTable(List.of(column("customer.id", "long", true, false)));

        var result = diff(baseline, target);

        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.objectPath()).isEqualTo("public.orders.customer.id"));
        assertThat(result.knownTableKeys()).containsExactly("public.orders");
        assertThat(result.reachedTableKeys()).containsExactly("public.orders");
    }

    @Test
    void theSchemaComparisonIsIncompleteWhenTheFindingsCapCutsIt() {
        var baseline = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("a", List.of()), new DatabaseSchemaView.Schema("b", List.of())));
        var target = new DatabaseSchemaView(List.of());

        var result = SchemaDriftDiffer.diff(baseline, target, new SchemaDriftDiffer.DiffLimits(500, 1), () -> false);

        // One missing schema was recorded, the other dropped: resolving anything would be a guess.
        assertThat(result.findings()).hasSize(1);
        assertThat(result.schemaLevelComplete()).isFalse();
    }
}
