package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.UUID;

/**
 * Calls the Anthropic Messages API via Spring AI's {@link ChatModel}. Instances are constructed by
 * {@code AiAnalyzerStrategyHolder} from the per-org {@code ai_config} row (provider, model, base
 * URL, API key). Not a Spring bean.
 */
@RequiredArgsConstructor
class AnthropicAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAnalyzerStrategy.class);
    private static final String SYSTEM_PROMPT_PREAMBLE = """
            You analyze SQL for security and performance risks. Always reply with a single JSON object \
            matching the exact schema described in the user's message. Do not wrap the JSON in markdown.""";
    private static final String SQL_GENERATION_PREAMBLE = """
            You translate natural-language requests into SQL. Always reply with a single JSON object \
            {"sql": "..."} matching the schema in the user's message. Do not wrap the JSON in markdown.""";

    private final ChatModel chatModel;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final SystemPromptSource promptSource;
    private final SqlGenerationResponseParser sqlGenerationParser;
    private final RagRetriever ragRetriever;

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                                    String costEstimateContext, String language, UUID aiConfigId) {
        var ragContext = ragRetriever.retrieve(sql);
        var userPrompt = promptRenderer.render(promptSource.template(), sql, dbType, schemaContext,
                ragContext, costEstimateContext, language);

        log.debug("Calling Anthropic via Spring AI: prompt_chars={}", userPrompt.length());

        var call = ChatModelInvoker.invoke(chatModel, SYSTEM_PROMPT_PREAMBLE, userPrompt, "Anthropic");

        log.debug("Anthropic response: model={}, input_tokens={}, output_tokens={}",
                call.model(), call.promptTokens(), call.completionTokens());

        return responseParser.parse(call.text(), AiProviderType.ANTHROPIC, call.model(),
                call.promptTokens(), call.completionTokens());
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        var ragContext = ragRetriever.retrieve(prompt);
        var userPrompt = promptRenderer.renderGeneration(prompt, dbType, schemaContext, ragContext, language);
        log.debug("Calling Anthropic via Spring AI for SQL generation: prompt_chars={}", userPrompt.length());
        var call = ChatModelInvoker.invoke(chatModel, SQL_GENERATION_PREAMBLE, userPrompt, "Anthropic");
        return sqlGenerationParser.parse(call.text(), AiProviderType.ANTHROPIC, call.model(),
                call.promptTokens(), call.completionTokens());
    }
}
