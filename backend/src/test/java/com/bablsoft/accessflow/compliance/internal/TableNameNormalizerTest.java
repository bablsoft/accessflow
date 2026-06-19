package com.bablsoft.accessflow.compliance.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableNameNormalizerTest {

    @Test
    void normalizeStripsQuotesLowercasesAndTrims() {
        assertThat(TableNameNormalizer.normalize("  \"Public\".\"Users\" ")).isEqualTo("public.users");
        assertThat(TableNameNormalizer.normalize("`Orders`")).isEqualTo("orders");
        assertThat(TableNameNormalizer.normalize("[dbo].[Customers]")).isEqualTo("dbo.customers");
        assertThat(TableNameNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void suffixReturnsBareTableName() {
        assertThat(TableNameNormalizer.suffix("public.users")).isEqualTo("users");
        assertThat(TableNameNormalizer.suffix("users")).isEqualTo("users");
    }

    @Test
    void matchesExactQualifiedNames() {
        assertThat(TableNameNormalizer.matches("public.users", "public.users")).isTrue();
        assertThat(TableNameNormalizer.matches("public.users", "public.orders")).isFalse();
    }

    @Test
    void matchesWhenEitherSideIsBare() {
        assertThat(TableNameNormalizer.matches("public.users", "users")).isTrue();
        assertThat(TableNameNormalizer.matches("users", "public.users")).isTrue();
        assertThat(TableNameNormalizer.matches("users", "users")).isTrue();
    }

    @Test
    void doesNotMatchAcrossDifferentSchemas() {
        assertThat(TableNameNormalizer.matches("sales.orders", "archive.orders")).isFalse();
    }

    @Test
    void emptyIdentifiersNeverMatch() {
        assertThat(TableNameNormalizer.matches("", "users")).isFalse();
        assertThat(TableNameNormalizer.matches("users", "")).isFalse();
    }
}
