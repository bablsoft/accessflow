package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiEmbeddingModelFactoryTest {

    private final SpringAiEmbeddingModelFactory factory =
            new SpringAiEmbeddingModelFactory(RestClient.create(), JsonMapper.builder().build());

    @Test
    void buildsOpenAiEmbeddingModel() {
        var model = factory.create(AiProviderType.OPENAI, "sk-test", "text-embedding-3-small", null, null);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsOpenAiCompatibleEmbeddingModelWithBaseUrl() {
        var model = factory.create(AiProviderType.OPENAI_COMPATIBLE, "sk", "nomic",
                "https://api.example.com/v1", null);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsHuggingFaceEmbeddingModel() {
        var model = factory.create(AiProviderType.HUGGING_FACE, "hf_token", "bge", null, null);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsOllamaEmbeddingModelWithDefaultBaseUrl() {
        var model = factory.create(AiProviderType.OLLAMA, "not-needed", "nomic-embed-text", null, null);

        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void buildsOllamaEmbeddingModelWithCustomBaseUrl() {
        var model = factory.create(AiProviderType.OLLAMA, "not-needed", "nomic-embed-text",
                "http://ollama:11434", null);

        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void buildsVoyageEmbeddingModel() {
        var model = factory.create(AiProviderType.VOYAGE, "pa-key", "voyage-4", null, 1024);

        // Not the OpenAI client: Voyage needs input_type, which the OpenAI wire format cannot carry.
        assertThat(model).isInstanceOf(VoyageEmbeddingModel.class);
        assertThat(model.dimensions()).isEqualTo(1024);
    }

    @Test
    void passesTheRequestedDimensionToTheOpenAiClient() {
        var model = factory.create(AiProviderType.OPENAI, "sk", "text-embedding-3-large", null, 512);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
        assertThat(((OpenAiEmbeddingModel) model).getOptions().getDimensions()).isEqualTo(512);
    }

    @Test
    void omitsTheDimensionWhenNoneIsRequested() {
        var model = factory.create(AiProviderType.OPENAI, "sk", "text-embedding-3-small", null, null);

        assertThat(((OpenAiEmbeddingModel) model).getOptions().getDimensions()).isNull();
    }

    @Test
    void rejectsAnthropicAsEmbeddingProvider() {
        assertThatThrownBy(() -> factory.create(AiProviderType.ANTHROPIC, "k", "m", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC");
    }
}
