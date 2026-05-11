package com.partqam.accessflow.ai.internal;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds a Spring AI {@link ChatModel} for one of the three supported providers. Plain interface
 * with one concrete bean ({@link SpringAiChatModelFactory}); split out from
 * {@link AiAnalyzerStrategyHolder} so the holder is unit-testable without spinning up real
 * provider clients.
 */
interface ChatModelFactory {

    ChatModel anthropic(String apiKey, String baseUrl, String model, int maxCompletionTokens, int timeoutMs);

    ChatModel openAi(String apiKey, String baseUrl, String model, int maxCompletionTokens, int timeoutMs);

    ChatModel ollama(String baseUrl, String model, int maxCompletionTokens);
}
