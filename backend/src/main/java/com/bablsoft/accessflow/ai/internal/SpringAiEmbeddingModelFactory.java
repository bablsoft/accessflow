package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

/**
 * Production {@link EmbeddingModelFactory}: builds Spring AI 2.0 embedding models programmatically
 * from an {@code ai_config}'s embedding settings. Spring AI's OpenAI integration wraps the official
 * OpenAI Java SDK, so the API key / base URL travel on {@link OpenAiEmbeddingOptions} (mirroring how
 * {@code SpringAiChatModelFactory} configures the chat model).
 */
@Component
class SpringAiEmbeddingModelFactory implements EmbeddingModelFactory {

    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    @Override
    public EmbeddingModel create(AiProviderType provider, String apiKey, String model, String baseUrl) {
        return switch (provider) {
            case OPENAI, OPENAI_COMPATIBLE, HUGGING_FACE -> openAi(apiKey, model, baseUrl);
            case OLLAMA -> ollama(model, baseUrl);
            case ANTHROPIC -> throw new IllegalArgumentException(
                    "ANTHROPIC is not a valid embedding provider");
        };
    }

    private EmbeddingModel openAi(String apiKey, String model, String baseUrl) {
        var options = OpenAiEmbeddingOptions.builder();
        options.model(model);
        options.apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            options.baseUrl(baseUrl);
        }
        return new OpenAiEmbeddingModel(options.build());
    }

    private EmbeddingModel ollama(String model, String baseUrl) {
        var api = OllamaApi.builder()
                .baseUrl((baseUrl == null || baseUrl.isBlank()) ? DEFAULT_OLLAMA_BASE_URL : baseUrl)
                .build();
        var options = OllamaEmbeddingOptions.builder().model(model).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
