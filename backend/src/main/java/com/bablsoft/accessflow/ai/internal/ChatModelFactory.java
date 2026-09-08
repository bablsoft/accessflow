package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds a Spring AI {@link ChatModel} for one of the three supported providers. Plain interface
 * with one concrete bean ({@link SpringAiChatModelFactory}); split out from
 * {@link AiAnalyzerStrategyHolder} so the holder is unit-testable without spinning up real
 * provider clients.
 *
 * <p>Anthropic and OpenAI fall back to Spring AI's built-in default base URL when {@code baseUrl}
 * is null/blank, and honor a stored one when it is set — an org fronting either provider with a
 * gateway or proxy configures it on the {@code ai_config} row. {@code OPENAI_COMPATIBLE} (vLLM, LM
 * Studio, Together, Groq, …) requires one. Ollama always self-hosts, so it requires one too.
 */
interface ChatModelFactory {

    ChatModel anthropic(String apiKey, String model, int maxCompletionTokens, int timeoutMs, String baseUrl);

    ChatModel openAi(String apiKey, String model, int maxCompletionTokens, int timeoutMs, String baseUrl);

    ChatModel ollama(String baseUrl, String model, int maxCompletionTokens);
}
