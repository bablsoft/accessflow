package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.RagConnectionTestResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagTestResponseTest {

    @Test
    void mapsOkResult() {
        var response = RagTestResponse.from(RagConnectionTestResult.ok("reachable", 1536));

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.detail()).isEqualTo("reachable");
        assertThat(response.embeddingDimensions()).isEqualTo(1536);
    }

    @Test
    void mapsErrorResult() {
        var response = RagTestResponse.from(RagConnectionTestResult.error("401 unauthorized"));

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.detail()).isEqualTo("401 unauthorized");
        assertThat(response.embeddingDimensions()).isNull();
    }
}
