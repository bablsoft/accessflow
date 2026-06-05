package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Shared Spring AI {@link ChatModel} call used by the SQL-generation path of every provider adapter.
 * Handles the call, empty-response guards and token/model extraction so each adapter's
 * {@code generateSql} stays a thin wrapper. The analysis path keeps its own per-adapter call to
 * avoid disturbing existing behaviour.
 */
final class ChatModelInvoker {

    private ChatModelInvoker() {
    }

    record Invocation(String text, String model, int promptTokens, int completionTokens) {
    }

    static Invocation invoke(ChatModel chatModel, String systemPreamble, String userPrompt,
                             String providerLabel) {
        var request = new Prompt(List.of(
                new SystemMessage(systemPreamble),
                new UserMessage(userPrompt)));
        ChatResponse response;
        try {
            response = chatModel.call(request);
        } catch (RuntimeException e) {
            throw new AiAnalysisException(providerLabel + " API call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new AiAnalysisException(providerLabel + " API returned an empty response");
        }
        var text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AiAnalysisException(providerLabel + " API returned an empty message");
        }
        int promptTokens = 0;
        int completionTokens = 0;
        String model = "";
        var metadata = response.getMetadata();
        if (metadata != null) {
            var usage = metadata.getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            }
            if (metadata.getModel() != null) {
                model = metadata.getModel();
            }
        }
        return new Invocation(text, model, promptTokens, completionTokens);
    }
}
