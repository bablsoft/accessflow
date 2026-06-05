package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Builds a Spring AI {@link EmbeddingModel} for an {@code ai_config}'s dedicated embedding settings
 * (AF-336). Mirrors {@link ChatModelFactory}: split out from the holder / vector-store factory so
 * they stay unit-testable without real provider clients.
 *
 * <p>Embeddings are independent of the chat provider — an Anthropic chat config still embeds via
 * OpenAI / Ollama. {@code ANTHROPIC} is not a valid embedding provider (no embeddings API) and is
 * rejected by the config service before this factory is reached.
 */
interface EmbeddingModelFactory {

    /**
     * @param provider embedding provider (OPENAI / OPENAI_COMPATIBLE / HUGGING_FACE / OLLAMA)
     * @param apiKey   decrypted API key (may be a placeholder for keyless self-hosted backends)
     * @param model    embedding model identifier
     * @param baseUrl  custom base URL (OPENAI_COMPATIBLE / HUGGING_FACE / OLLAMA); null = provider default
     */
    EmbeddingModel create(AiProviderType provider, String apiKey, String model, String baseUrl);
}
