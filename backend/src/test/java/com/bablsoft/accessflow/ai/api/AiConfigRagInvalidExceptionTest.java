package com.bablsoft.accessflow.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigRagInvalidExceptionTest {

    @Test
    void carriesTheMessageKey() {
        var ex = new AiConfigRagInvalidException("error.ai_config.rag.embedding_model_required");

        assertThat(ex.messageKey()).isEqualTo("error.ai_config.rag.embedding_model_required");
        assertThat(ex.getMessage()).isEqualTo("error.ai_config.rag.embedding_model_required");
    }
}
