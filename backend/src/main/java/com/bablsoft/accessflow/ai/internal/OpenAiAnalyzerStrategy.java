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
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                                    String costEstimateContext, String language, UUID aiConfigId) {
        var ragContext = ragRetriever.retrieve(sql);
        var userPrompt = promptRenderer.render(promptSource.template(), sql, dbType, schemaContext,
                ragContext, costEstimateContext, language);

        log.debug("Calling OpenAI via Spring AI: prompt_chars={}", userPrompt.length());

        var call = ChatModelInvoker.invoke(chatModel, SYSTEM_PROMPT_PREAMBLE, userPrompt, "OpenAI");

        log.debug("OpenAI response: model={}, input_tokens={}, output_tokens={}",
                call.model(), call.promptTokens(), call.completionTokens());

        return responseParser.parse(call.text(), providerType, call.model(),
                call.promptTokens(), call.completionTokens());
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
