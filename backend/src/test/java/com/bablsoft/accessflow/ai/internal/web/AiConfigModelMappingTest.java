package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiConfigModelView;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping tests for the AF-450 orchestration-member web records. */
class AiConfigModelMappingTest {

    @Test
    void requestMapsToCommandPreservingAllFields() {
        var id = UUID.randomUUID();
        var request = new AiConfigModelRequest(id, AiProviderType.OPENAI_COMPATIBLE, "qwen2.5",
                "http://vllm:8000/v1", "sk-member", 2.5, true);

        var command = request.toCommand();

        assertThat(command.id()).isEqualTo(id);
        assertThat(command.provider()).isEqualTo(AiProviderType.OPENAI_COMPATIBLE);
        assertThat(command.model()).isEqualTo("qwen2.5");
        assertThat(command.endpoint()).isEqualTo("http://vllm:8000/v1");
        assertThat(command.apiKey()).isEqualTo("sk-member");
        assertThat(command.weight()).isEqualTo(2.5);
        assertThat(command.enabled()).isTrue();
    }

    @Test
    void responseRendersMaskedKeyWhenStored() {
        var id = UUID.randomUUID();
        var view = new AiConfigModelView(id, AiProviderType.OLLAMA, "llama3", null, true, 1.0, true);

        var response = AiConfigModelResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.provider()).isEqualTo(AiProviderType.OLLAMA);
        assertThat(response.model()).isEqualTo("llama3");
        assertThat(response.apiKey()).isEqualTo(UpdateAiConfigCommand.MASKED_API_KEY);
        assertThat(response.weight()).isEqualTo(1.0);
        assertThat(response.enabled()).isTrue();
    }

    @Test
    void responseOmitsKeyWhenNoneStored() {
        var view = new AiConfigModelView(UUID.randomUUID(), AiProviderType.OPENAI, "gpt", null,
                false, 3.0, false);

        var response = AiConfigModelResponse.from(view);

        assertThat(response.apiKey()).isNull();
        assertThat(response.enabled()).isFalse();
        assertThat(response.weight()).isEqualTo(3.0);
    }
}
