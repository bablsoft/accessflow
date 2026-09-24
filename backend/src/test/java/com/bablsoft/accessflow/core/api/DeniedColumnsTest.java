package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeniedColumnsTest {

    private static final List<String> DENIED = List.of("public.customer.national_id");

    @Test
    void normalizeStripsQuotesLowercasesAndDropsBlanksAndDuplicates() {
        var input = new java.util.ArrayList<String>();
        input.add(" \"Public\".[Customer].`SSN` ");
        input.add("public.customer.ssn");
        input.add("  ");
        input.add(null);

        assertThat(DeniedColumns.normalize(input)).containsExactly("public.customer.ssn");
        assertThat(DeniedColumns.normalize(null)).isEmpty();
        assertThat(DeniedColumns.normalize(List.of())).isEmpty();
    }

    @Test
    void isQualifiedAcceptsTwoOrThreeParts() {
        assertThat(DeniedColumns.isQualified("customer.ssn")).isTrue();
        assertThat(DeniedColumns.isQualified("public.customer.ssn")).isTrue();
        assertThat(DeniedColumns.isQualified("ssn")).isFalse();
        assertThat(DeniedColumns.isQualified("a.b.c.d")).isFalse();
        assertThat(DeniedColumns.isQualified("customer.")).isFalse();
        assertThat(DeniedColumns.isQualified(".ssn")).isFalse();
        assertThat(DeniedColumns.isQualified(" ")).isFalse();
        assertThat(DeniedColumns.isQualified(null)).isFalse();
    }

    @Test
    void namedColumnOnTheDeniedTableIsRejected() {
        var parsed = parsed(QueryType.SELECT, new ColumnReference(Set.of("customer"), "national_id"));

        assertThat(DeniedColumns.rejected(DENIED, parsed)).containsExactly("public.customer.national_id");
    }

    @Test
    void schemaMismatchIsNotRejectedButMissingSchemaFailsClosed() {
        assertThat(DeniedColumns.rejected(DENIED, parsed(QueryType.SELECT,
                new ColumnReference(Set.of("other.customer"), "national_id")))).isEmpty();
        assertThat(DeniedColumns.rejected(List.of("customer.national_id"), parsed(QueryType.SELECT,
                new ColumnReference(Set.of("other.customer"), "national_id"))))
                .containsExactly("customer.national_id");
        assertThat(DeniedColumns.rejected(DENIED, parsed(QueryType.SELECT,
                new ColumnReference(Set.of("db.public.customer"), "national_id"))))
                .containsExactly("public.customer.national_id");
    }

    @Test
    void otherColumnsAndTablesAreAllowed() {
        assertThat(DeniedColumns.rejected(DENIED, parsed(QueryType.SELECT,
                new ColumnReference(Set.of("customer"), "name"),
                new ColumnReference(Set.of("orders"), "national_id")))).isEmpty();
    }

    @Test
    void wildcardOnTheDeniedTableIsRejected() {
        assertThat(DeniedColumns.rejected(DENIED, parsed(QueryType.SELECT,
                ColumnReference.wildcard(Set.of("orders", "public.customer")))))
                .containsExactly("public.customer.national_id");
    }

    @Test
    void unanalyzedDataQueryRejectsEveryDeniedEntry() {
        var unanalyzed = new SqlParseResult(QueryType.SELECT, "db.customer.find({})");

        assertThat(DeniedColumns.rejected(List.of("b.c", "a.b.c"), unanalyzed))
                .containsExactly("a.b.c", "b.c");
    }

    @Test
    void analyzedDdlIsCheckedThroughItsEmbeddedQuery() {
        var ctas = new SqlParseResult(QueryType.DDL, false, List.of("sql"), Set.of(), false, false,
                Set.of(new ColumnReference(Set.of("public.customer"), "national_id")), true);

        assertThat(DeniedColumns.rejected(DENIED, ctas)).containsExactly("public.customer.national_id");
    }

    @Test
    void wholeTableReadReachesEveryEntryOnThatTable() {
        assertThat(DeniedColumns.rejectedForWholeTable(
                List.of("customer.ssn", "public.customer.national_id", "orders.card"),
                "\"Public\".Customer"))
                .containsExactly("customer.ssn", "public.customer.national_id");
        assertThat(DeniedColumns.rejectedForWholeTable(List.of(), "customer")).isEmpty();
        assertThat(DeniedColumns.rejectedForWholeTable(DENIED, " ")).isEmpty();
        assertThat(DeniedColumns.rejectedForWholeTable(DENIED, null)).isEmpty();
    }

    @Test
    void intersectMeetsEntriesByTheColumnTheyName() {
        assertThat(DeniedColumns.intersect(List.of("users.ssn", "users.email"),
                List.of("public.users.ssn")))
                .containsExactly("public.users.ssn");
        assertThat(DeniedColumns.intersect(List.of("public.users.ssn"), List.of("Users.SSN")))
                .containsExactly("public.users.ssn");
        assertThat(DeniedColumns.intersect(List.of("a.users.ssn"), List.of("b.users.ssn"))).isEmpty();
        assertThat(DeniedColumns.intersect(List.of("users.ssn"), List.of("orders.ssn"))).isEmpty();
        assertThat(DeniedColumns.intersect(List.of("users.ssn"), List.of("users.email"))).isEmpty();
        assertThat(DeniedColumns.intersect(List.of("users.ssn"), List.of())).isEmpty();
    }

    @Test
    void ddlTouchingATableWithADeniedColumnIsRefused() {
        var rename = new SqlParseResult(QueryType.DDL, false, List.of("sql"),
                Set.of("public.customer"), false, false, Set.of(), true);
        var elsewhere = new SqlParseResult(QueryType.DDL, false, List.of("sql"),
                Set.of("orders"), false, false, Set.of(), true);

        assertThat(DeniedColumns.rejected(DENIED, rename)).containsExactly("public.customer.national_id");
        assertThat(DeniedColumns.rejected(DENIED, elsewhere)).isEmpty();
    }

    @Test
    void unanalyzedDdlFailsClosedAndOtherIsNeverChecked() {
        assertThat(DeniedColumns.rejected(DENIED, new SqlParseResult(QueryType.DDL,
                "ALTER VIEW v AS SELECT national_id FROM customer")))
                .containsExactly("public.customer.national_id");
        assertThat(DeniedColumns.rejected(DENIED, new SqlParseResult(QueryType.OTHER, "CALL x()")))
                .isEmpty();
    }

    @Test
    void emptyDenyListOrNullParseRejectsNothing() {
        var parsed = parsed(QueryType.SELECT, ColumnReference.wildcard(Set.of("customer")));

        assertThat(DeniedColumns.rejected(List.of(), parsed)).isEmpty();
        assertThat(DeniedColumns.rejected(null, parsed)).isEmpty();
        assertThat(DeniedColumns.rejected(DENIED, null)).isEmpty();
    }

    @Test
    void dmlTypesAreEnforced() {
        for (var type : List.of(QueryType.INSERT, QueryType.UPDATE, QueryType.DELETE)) {
            assertThat(DeniedColumns.rejected(DENIED, parsed(type,
                    new ColumnReference(Set.of("public.customer"), "national_id")))).hasSize(1);
        }
    }

    @Test
    void columnReferenceRejectsBlankColumnAndDefaultsTables() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ColumnReference(Set.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ColumnReference(Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        var ref = new ColumnReference(null, "x");
        assertThat(ref.candidateTables()).isEmpty();
        assertThat(ref.isWildcard()).isFalse();
        assertThat(ColumnReference.wildcard(Set.of("t")).isWildcard()).isTrue();
    }

    private static SqlParseResult parsed(QueryType type, ColumnReference... refs) {
        return new SqlParseResult(type, false, List.of("sql"), Set.of(), false, false,
                Set.of(refs), true);
    }
}
