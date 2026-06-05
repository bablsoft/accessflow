package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiEmbeddingModelFactoryTest {

    private final SpringAiEmbeddingModelFactory factory = new SpringAiEmbeddingModelFactory();

    @Test
    void buildsOpenAiEmbeddingModel() {
        var model = factory.create(AiProviderType.OPENAI, "sk-test", "text-embedding-3-small", null);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsOpenAiCompatibleEmbeddingModelWithBaseUrl() {
        var model = factory.create(AiProviderType.OPENAI_COMPATIBLE, "sk", "nomic",
                "https://api.example.com/v1");

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsHuggingFaceEmbeddingModel() {
        var model = factory.create(AiProviderType.HUGGING_FACE, "hf_token", "bge", null);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void buildsOllamaEmbeddingModelWithDefaultBaseUrl() {
        var model = factory.create(AiProviderType.OLLAMA, "not-needed", "nomic-embed-text", null);

        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void buildsOllamaEmbeddingModelWithCustomBaseUrl() {
        var model = factory.create(AiProviderType.OLLAMA, "not-needed", "nomic-embed-text",
                "http://ollama:11434");

        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void rejectsAnthropicAsEmbeddingProvider() {
        assertThatThrownBy(() -> factory.create(AiProviderType.ANTHROPIC, "k", "m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC");
    }
}
