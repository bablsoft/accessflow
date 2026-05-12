package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Production {@link ChatModelFactory}: builds Spring AI 2.0 chat models programmatically from
 * the per-org {@code ai_config} row.
 */
@Component
class SpringAiChatModelFactory implements ChatModelFactory {

    @Override
    public ChatModel anthropic(String apiKey, String model, int maxCompletionTokens, int timeoutMs) {
        var options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxCompletionTokens)
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        return AnthropicChatModel.builder()
                .options(options)
                .build();
    }

    @Override
    public ChatModel openAi(String apiKey, String model, int maxCompletionTokens, int timeoutMs) {
        var options = OpenAiChatOptions.builder()
                .model(model)
                .maxCompletionTokens(maxCompletionTokens)
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Override
    public ChatModel ollama(String baseUrl, String model, int maxCompletionTokens) {
        var api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();
        var options = OllamaChatOptions.builder()
                .model(model)
                .numPredict(maxCompletionTokens)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
    }
}
