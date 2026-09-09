package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoyageConfigurationTest {

    @Test
    void buildsTheVoyageRestClient() {
        assertThat(new VoyageConfiguration().voyageRestClient()).isNotNull();
    }
}
