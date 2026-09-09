package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoyageEmbeddingOptionsTest {

    @Test
    void ofCarriesOnlyTheInputType() {
        var options = VoyageEmbeddingOptions.of(VoyageInputType.DOCUMENT);

        assertThat(options.inputType()).isEqualTo(VoyageInputType.DOCUMENT);
        assertThat(options.getModel()).isNull();
        assertThat(options.getDimensions()).isNull();
    }

    @Test
    void exposesModelAndDimensionsThroughTheSpringAiAccessors() {
        var options = new VoyageEmbeddingOptions("voyage-4", 512, VoyageInputType.QUERY);

        assertThat(options.getModel()).isEqualTo("voyage-4");
        assertThat(options.getDimensions()).isEqualTo(512);
        assertThat(options.inputType()).isEqualTo(VoyageInputType.QUERY);
    }

    @Test
    void wireValuesMatchTheVoyageApi() {
        assertThat(VoyageInputType.DOCUMENT.wireValue()).isEqualTo("document");
        assertThat(VoyageInputType.QUERY.wireValue()).isEqualTo("query");
    }
}
