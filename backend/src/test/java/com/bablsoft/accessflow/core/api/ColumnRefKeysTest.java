package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnRefKeysTest {

    @Test
    void parsesABareColumn() {
        var keys = ColumnRefKeys.parse("email");
        assertThat(keys.full()).isNull();
        assertThat(keys.table()).isNull();
        assertThat(keys.bare()).isEqualTo("email");
    }

    @Test
    void parsesTableDotColumn() {
        var keys = ColumnRefKeys.parse("customers.email");
        assertThat(keys.full()).isNull();
        assertThat(keys.table()).isEqualTo("customers.email");
        assertThat(keys.bare()).isEqualTo("email");
    }

    @Test
    void parsesSchemaDotTableDotColumn() {
        var keys = ColumnRefKeys.parse("public.customers.email");
        assertThat(keys.full()).isEqualTo("public.customers.email");
        assertThat(keys.table()).isEqualTo("customers.email");
        assertThat(keys.bare()).isEqualTo("email");
    }

    @Test
    void keepsTheLastThreeSegmentsOfAnOverQualifiedRef() {
        var keys = ColumnRefKeys.parse("db.public.customers.email");
        assertThat(keys.full()).isEqualTo("public.customers.email");
        assertThat(keys.table()).isEqualTo("customers.email");
        assertThat(keys.bare()).isEqualTo("email");
    }

    @Test
    void parsingLowercasesAndTrims() {
        var keys = ColumnRefKeys.parse("  Public.Customers.Email  ");
        assertThat(keys.full()).isEqualTo("public.customers.email");
    }

    @Test
    void matchLevelPrefersTheMostSpecificMatch() {
        assertThat(ColumnRefKeys.parse("public.customers.email")
                .matchLevel("public", "customers", "email")).isEqualTo(3);
        assertThat(ColumnRefKeys.parse("customers.email")
                .matchLevel("public", "customers", "email")).isEqualTo(2);
        assertThat(ColumnRefKeys.parse("email")
                .matchLevel("public", "customers", "email")).isEqualTo(1);
    }

    @Test
    void matchLevelIsZeroOnlyWhenEvenTheColumnNameDiffers() {
        assertThat(ColumnRefKeys.parse("customers.ssn")
                .matchLevel("public", "customers", "email")).isZero();
    }

    @Test
    void aRefForAnotherTableStillMatchesTheBareColumnName() {
        // Long-standing behaviour of the mask resolver: a qualified ref falls back to bare-name
        // matching, so an `orders.email` policy also masks a `customers.email` result column.
        // Over-masking is the safe direction for a masking policy, so the simulator reports it
        // rather than quietly diverging from what execution actually does.
        assertThat(ColumnRefKeys.parse("orders.email")
                .matchLevel("public", "customers", "email")).isEqualTo(1);
    }

    @Test
    void withNoSchemaOrTableEveryRefDegradesToBareNameMatching() {
        // The policy simulator's case: persisted result columns carry a name and a JDBC type but
        // no schema or table, so every policy is compared on the column name alone. That is what
        // the COLUMN_MATCH_BARE_NAME caveat tells the reader.
        assertThat(ColumnRefKeys.parse("email").matchLevel(null, null, "email")).isEqualTo(1);
        assertThat(ColumnRefKeys.parse("customers.email").matchLevel(null, null, "email"))
                .isEqualTo(1);
        assertThat(ColumnRefKeys.parse("public.customers.email").matchLevel(null, null, "email"))
                .isEqualTo(1);
        assertThat(ColumnRefKeys.parse("customers.ssn").matchLevel(null, null, "email")).isZero();
    }

    @Test
    void aQualifiedRefStillMatchesWhenOnlyTheTableIsKnown() {
        assertThat(ColumnRefKeys.parse("customers.email")
                .matchLevel(null, "customers", "email")).isEqualTo(2);
    }
}
