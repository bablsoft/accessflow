package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds a Spring AI {@link ChatModel} for one of the three supported providers. Plain interface
 * with one concrete bean ({@link SpringAiChatModelFactory}); split out from
 * {@link AiAnalyzerStrategyHolder} so the holder is unit-testable without spinning up real
 * provider clients.
 *
 * <p>Anthropic uses Spring AI's built-in default base URL. OpenAI defaults to it too when
 * {@code baseUrl} is null/blank (the {@code OPENAI} provider), but honors a custom base URL when
 * supplied (the {@code OPENAI_COMPATIBLE} provider — vLLM, LM Studio, Together, Groq, …). Ollama
 * always self-hosts, so it requires a base URL.
 */
interface ChatModelFactory {

    ChatModel anthropic(String apiKey, String model, int maxCompletionTokens, int timeoutMs);

    ChatModel openAi(String apiKey, String model, int maxCompletionTokens, int timeoutMs, String baseUrl);

    ChatModel ollama(String baseUrl, String model, int maxCompletionTokens);
}
