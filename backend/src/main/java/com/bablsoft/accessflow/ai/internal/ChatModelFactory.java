package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds a Spring AI {@link ChatModel} for one of the three supported providers. Plain interface
 * with one concrete bean ({@link SpringAiChatModelFactory}); split out from
 * {@link AiAnalyzerStrategyHolder} so the holder is unit-testable without spinning up real
 * provider clients.
 *
 * <p>OpenAI and Anthropic use Spring AI's built-in default base URLs — the user-supplied
 * {@code endpoint} column on {@code ai_config} is honored only for Ollama, where the user
 * self-hosts the service.
 */
interface ChatModelFactory {

    ChatModel anthropic(String apiKey, String model, int maxCompletionTokens, int timeoutMs);

    ChatModel openAi(String apiKey, String model, int maxCompletionTokens, int timeoutMs);

    ChatModel ollama(String baseUrl, String model, int maxCompletionTokens);
}
