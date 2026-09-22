package com.bablsoft.accessflow.schemachange.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangePropertiesTest {

    @Test
    void nullFallsBackToTheDefault() {
        assertThat(new SchemaChangeProperties(null).maxStatements())
                .isEqualTo(SchemaChangeProperties.DEFAULT_MAX_STATEMENTS).isEqualTo(50);
    }

    @Test
    void zeroAndNegativeFallBackToTheDefault() {
        assertThat(new SchemaChangeProperties(0).maxStatements()).isEqualTo(50);
        assertThat(new SchemaChangeProperties(-7).maxStatements()).isEqualTo(50);
    }

    @Test
    void explicitValueIsKept() {
        assertThat(new SchemaChangeProperties(120).maxStatements()).isEqualTo(120);
        assertThat(new SchemaChangeProperties(1).maxStatements()).isEqualTo(1);
    }
}
