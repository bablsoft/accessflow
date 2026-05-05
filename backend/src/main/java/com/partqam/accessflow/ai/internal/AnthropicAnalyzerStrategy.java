package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Calls the Anthropic Messages API via Spring AI's auto-configured {@link ChatModel}.
 *
 * <p>Provider selection (model name, API key, base URL, max tokens) is delegated to Spring AI
 * configuration under {@code spring.ai.anthropic.*}. This bean activates when
 * {@code accessflow.ai.provider=anthropic} (the default).
 */
@Service
@ConditionalOnProperty(name = "accessflow.ai.provider", havingValue = "anthropic", matchIfMissing = true)
@RequiredArgsConstructor
class AnthropicAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAnalyzerStrategy.class);
    private static final String SYSTEM_PROMPT_PREAMBLE = """
            You analyze SQL for security and performance risks. Always reply with a single JSON object \
            matching the exact schema described in the user's message. Do not wrap the JSON in markdown.""";

    private final ChatModel chatModel;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext) {
        var userPrompt = promptRenderer.render(sql, dbType, schemaContext);
        var prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT_PREAMBLE),
                new UserMessage(userPrompt)));

        log.debug("Calling Anthropic via Spring AI: prompt_chars={}", userPrompt.length());

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException e) {
            throw new AiAnalysisException("Anthropic API call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new AiAnalysisException("Anthropic API returned an empty response");
        }

        var text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AiAnalysisException("Anthropic API returned an empty message");
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

        log.debug("Anthropic response: model={}, input_tokens={}, output_tokens={}",
                model, promptTokens, completionTokens);

        return responseParser.parse(text, AiProviderType.ANTHROPIC, model, promptTokens, completionTokens);
    }
}
