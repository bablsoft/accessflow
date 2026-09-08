package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Spring AI {@link ChatModel} call used by the SQL-generation path of every provider adapter
 * and by the in-app help chat runtime (AF-903). Handles the call, empty-response guards and
 * token/model extraction so each caller stays a thin wrapper. The analysis path keeps its own
 * per-adapter call to avoid disturbing existing behaviour.
 *
 * <p>Two entry points, one body: {@link #invoke(ChatModel, String, String, String)} is the
 * single-turn shape every adapter already uses, and delegates to
 * {@link #invoke(ChatModel, List, String)}, the multi-turn shape help chat needs. Sharing the body
 * is the point — the response guards and the usage extraction are exactly what a second, parallel
 * implementation would drift away from.
 *
 * <p>{@code public} rather than package-private because {@code ai.internal.help} is a sub-package
 * and gets no package-private access (epic AF-899 decision 5). It stays inside {@code ai.internal},
 * so it remains module-private to the rest of the application.
 */
public final class ChatModelInvoker {

    private ChatModelInvoker() {
    }

    public record Invocation(String text, String model, int promptTokens, int completionTokens) {
    }

    public static Invocation invoke(ChatModel chatModel, String systemPreamble, String userPrompt,
                                    String providerLabel) {
        return invoke(chatModel, List.of(
                new SystemMessage(systemPreamble),
                new UserMessage(userPrompt)), providerLabel);
    }

    /**
     * Calls the model with a whole conversation — a system preamble followed by alternating user and
     * assistant messages. The caller owns the ordering and any history capping; this method only
     * makes the call and reads the response.
     */
    public static Invocation invoke(ChatModel chatModel, List<Message> messages,
                                    String providerLabel) {
        var request = new Prompt(new ArrayList<>(messages));
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
