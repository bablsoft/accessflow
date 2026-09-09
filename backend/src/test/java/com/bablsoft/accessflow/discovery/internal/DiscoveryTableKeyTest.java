package com.bablsoft.accessflow.discovery.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryTableKeyTest {

    @Test
    @DisplayName("normalises a null schema to the empty string, matching COALESCE(schema_name, '')")
    void normalisesNullSchema() {
        assertThat(DiscoveryTableKey.of(null, "customers"))
                .isEqualTo(DiscoveryTableKey.of("", "customers"));
    }

    @Test
    @DisplayName("keeps a present schema verbatim")
    void keepsPresentSchema() {
        assertThat(DiscoveryTableKey.of("public", "customers").schemaName()).isEqualTo("public");
    }

    @Test
    @DisplayName("does not case-fold — findings persist the introspected spelling")
    void doesNotCaseFold() {
        assertThat(DiscoveryTableKey.of("public", "Customers"))
                .isNotEqualTo(DiscoveryTableKey.of("public", "customers"));
    }

    @Test
    @DisplayName("distinguishes the same table name in different schemas")
    void distinguishesSchemas() {
        assertThat(DiscoveryTableKey.of("sales", "orders"))
                .isNotEqualTo(DiscoveryTableKey.of("public", "orders"));
    }
}
