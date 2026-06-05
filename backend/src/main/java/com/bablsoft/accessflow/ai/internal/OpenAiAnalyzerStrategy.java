package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.UUID;

/**
 * Calls the OpenAI Chat Completions API via Spring AI's {@link ChatModel}. Instances are
 * constructed by {@code AiAnalyzerStrategyHolder} from the per-org {@code ai_config} row, for the
 * {@code OPENAI}, {@code OPENAI_COMPATIBLE} and {@code HUGGING_FACE} providers (identical wire
 * format; the latter two point the same client at a custom base URL — a self-hosted backend or the
 * Hugging Face Inference Providers router / local TGI). The {@code providerType} is recorded on the
 * resulting analysis so dashboards group by the actual configured provider. Not a Spring bean.
 */
@RequiredArgsConstructor
class OpenAiAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAnalyzerStrategy.class);
    private static final String SYSTEM_PROMPT_PREAMBLE = """
            You analyze SQL for security and performance risks. Always reply with a single JSON object \
            matching the exact schema described in the user's message. Do not wrap the JSON in markdown.""";
    private static final String SQL_GENERATION_PREAMBLE = """
            You translate natural-language requests into SQL. Always reply with a single JSON object \
            {"sql": "..."} matching the schema in the user's message. Do not wrap the JSON in markdown.""";

    private final AiProviderType providerType;
    private final ChatModel chatModel;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final SystemPromptSource promptSource;
    private final SqlGenerationResponseParser sqlGenerationParser;
    private final RagRetriever ragRetriever;

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                    UUID aiConfigId) {
        var ragContext = ragRetriever.retrieve(sql);
        var userPrompt = promptRenderer.render(promptSource.template(), sql, dbType, schemaContext,
                ragContext, language);
        var prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT_PREAMBLE),
                new UserMessage(userPrompt)));

        log.debug("Calling OpenAI via Spring AI: prompt_chars={}", userPrompt.length());

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException e) {
            throw new AiAnalysisException("OpenAI API call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new AiAnalysisException("OpenAI API returned an empty response");
        }

        var text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AiAnalysisException("OpenAI API returned an empty message");
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

        log.debug("OpenAI response: model={}, input_tokens={}, output_tokens={}",
                model, promptTokens, completionTokens);

        return responseParser.parse(text, providerType, model, promptTokens, completionTokens);
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        var ragContext = ragRetriever.retrieve(prompt);
        var userPrompt = promptRenderer.renderGeneration(prompt, dbType, schemaContext, ragContext, language);
        log.debug("Calling OpenAI via Spring AI for SQL generation: prompt_chars={}", userPrompt.length());
        var call = ChatModelInvoker.invoke(chatModel, SQL_GENERATION_PREAMBLE, userPrompt, "OpenAI");
        return sqlGenerationParser.parse(call.text(), providerType, call.model(),
                call.promptTokens(), call.completionTokens());
    }
}
