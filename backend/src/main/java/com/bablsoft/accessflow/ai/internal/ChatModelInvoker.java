package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Shared Spring AI {@link ChatModel} call used by every provider adapter — the analysis path, the
 * SQL-generation path and the in-app help chat runtime (AF-903). Handles the call, the response
 * guards and token/model extraction so each caller stays a thin wrapper.
 *
 * <p>Two entry points, one body: {@link #invoke(ChatModel, String, String, String)} is the
 * single-turn shape every adapter already uses, and delegates to
 * {@link #invoke(ChatModel, List, String)}, the multi-turn shape help chat needs. Sharing the body
 * is the point — the response guards and the usage extraction are exactly what a second, parallel
 * implementation would drift away from, and did: three adapters each carried their own copy that
 * read the response wrongly.
 *
 * <p><strong>The answer is the last non-blank generation, never the first.</strong> Spring AI's
 * Anthropic adapter appends the aggregated text generation <em>after</em> emitting one generation per
 * {@code thinking} / {@code redacted_thinking} content block, and a {@code redacted_thinking}
 * generation carries properties with no content at all. Reading {@code getResult()} — which is
 * {@code generations.get(0)} — therefore returned the model's reasoning as the answer on a thinking
 * model, or threw "empty message" when that first block was redacted. Providers that return a single
 * generation (OpenAI, Ollama) are unaffected by the scan.
 *
 * <p>{@code public} rather than package-private because {@code ai.internal.help} is a sub-package
 * and gets no package-private access (epic AF-899 decision 5). It stays inside {@code ai.internal},
 * so it remains module-private to the rest of the application.
 */
public final class ChatModelInvoker {

    private static final Logger log = LoggerFactory.getLogger(ChatModelInvoker.class);

    /** Anthropic's stop reason for a completion cut off by the token budget; OpenAI calls it "length". */
    private static final List<String> TRUNCATED_FINISH_REASONS = List.of("max_tokens", "length");

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
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            log.warn("{} returned no generations. {}", providerLabel, describe(response, messages));
            throw new AiAnalysisException(providerLabel + " API returned an empty response");
        }
        var answer = answerGeneration(response);
        if (answer == null) {
            log.warn("{} returned no usable text. {}", providerLabel, describe(response, messages));
            throw new AiAnalysisException(providerLabel + (truncated(response)
                    ? " API returned no text before exhausting max_completion_tokens"
                    : " API returned an empty message"));
        }
        if (isTruncated(answer)) {
            log.warn("{} truncated its answer at the configured max_completion_tokens. {}",
                    providerLabel, describe(response, messages));
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
        return new Invocation(answer.getOutput().getText(), model, promptTokens, completionTokens);
    }

    /**
     * The last generation carrying non-blank text, or {@code null} when none does. Walking backwards
     * finds Anthropic's aggregated text block, which is appended after any reasoning generations,
     * while leaving the single-generation providers on exactly the element they always returned.
     */
    private static Generation answerGeneration(ChatResponse response) {
        var generations = response.getResults();
        for (int i = generations.size() - 1; i >= 0; i--) {
            var generation = generations.get(i);
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            var text = generation.getOutput().getText();
            if (text != null && !text.isBlank()) {
                return generation;
            }
        }
        return null;
    }

    private static boolean truncated(ChatResponse response) {
        return response.getResults().stream().anyMatch(ChatModelInvoker::isTruncated);
    }

    private static boolean isTruncated(Generation generation) {
        if (generation == null || generation.getMetadata() == null) {
            return false;
        }
        var reason = generation.getMetadata().getFinishReason();
        return reason != null && TRUNCATED_FINISH_REASONS.contains(reason.toLowerCase(Locale.ROOT));
    }

    /**
     * A content-free description of the request and the response, for the operator log.
     *
     * <p>Sizes and shapes only, never text. The help-chat preamble carries the user's screen, their
     * permission names and their question, so logging prompt or completion content would put user
     * context into operator logs. What is here is what distinguishes the failure modes: the number of
     * generations and each one's property keys ({@code signature} = a thinking block, {@code data} =
     * a redacted thinking block) identify a misread response, and the finish reason plus the usage
     * counts identify an exhausted token budget or a provider refusal.
     */
    private static String describe(ChatResponse response, List<Message> messages) {
        var sb = new StringBuilder("request[messages=").append(messages.size());
        var chars = new EnumMap<MessageType, Integer>(MessageType.class);
        for (var message : messages) {
            var text = message.getText();
            chars.merge(message.getMessageType(), text == null ? 0 : text.length(), Integer::sum);
        }
        chars.forEach((type, count) -> sb.append(' ').append(type.getValue()).append("_chars=").append(count));
        sb.append(']');
        if (response == null) {
            return sb.append(" response[null]").toString();
        }
        var metadata = response.getMetadata();
        sb.append(" response[model=").append(metadata == null || metadata.getModel() == null
                ? "?" : metadata.getModel());
        if (metadata != null && metadata.getUsage() != null) {
            sb.append(" prompt_tokens=").append(metadata.getUsage().getPromptTokens())
                    .append(" completion_tokens=").append(metadata.getUsage().getCompletionTokens());
        }
        var generations = response.getResults();
        sb.append(" generations=").append(generations == null ? 0 : generations.size()).append(']');
        if (generations != null) {
            for (int i = 0; i < generations.size(); i++) {
                var generation = generations.get(i);
                sb.append(" gen[").append(i).append(" text_chars=");
                if (generation == null || generation.getOutput() == null
                        || generation.getOutput().getText() == null) {
                    sb.append("null");
                } else {
                    sb.append(generation.getOutput().getText().length());
                }
                if (generation != null && generation.getOutput() != null
                        && generation.getOutput().getMetadata() != null) {
                    sb.append(" properties=").append(generation.getOutput().getMetadata().keySet());
                }
                if (generation != null && generation.getMetadata() != null) {
                    sb.append(" finish_reason=").append(generation.getMetadata().getFinishReason());
                }
                sb.append(']');
            }
        }
        return sb.toString();
    }
}
