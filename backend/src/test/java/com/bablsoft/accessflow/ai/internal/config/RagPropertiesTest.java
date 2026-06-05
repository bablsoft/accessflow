package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagPropertiesTest {

    @Test
    void appliesDefaultsForNullValues() {
        var props = new RagProperties(null, null, null);

        assertThat(props.pgvectorDimensions()).isEqualTo(1536);
        assertThat(props.chunkSize()).isEqualTo(800);
        assertThat(props.maxDocumentChars()).isEqualTo(100_000);
    }

    @Test
    void appliesDefaultsForNonPositiveValues() {
        var props = new RagProperties(0, -5, 0);

        assertThat(props.pgvectorDimensions()).isEqualTo(1536);
        assertThat(props.chunkSize()).isEqualTo(800);
        assertThat(props.maxDocumentChars()).isEqualTo(100_000);
    }

    @Test
    void keepsProvidedValues() {
        var props = new RagProperties(768, 512, 50_000);

        assertThat(props.pgvectorDimensions()).isEqualTo(768);
        assertThat(props.chunkSize()).isEqualTo(512);
        assertThat(props.maxDocumentChars()).isEqualTo(50_000);
    }
}
