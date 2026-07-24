package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiGuardrailViolationException;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.DbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pre-call guardrail (AF-450). Rejects a submitted SQL / NL prompt that matches any configured
 * case-insensitive regex pattern <em>before</em> the delegate (orchestrator) runs, throwing
 * {@link AiGuardrailViolationException}. Empty patterns = pass-through. The post-call guardrail
 * (output-schema validation) is the existing strict {@code AiResponseParser}.
 */
class GuardrailAiAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAiAnalyzerStrategy.class);

    private final List<Pattern> patterns;
    private final AiAnalyzerStrategy delegate;
    private final MessageSource messageSource;

    GuardrailAiAnalyzerStrategy(List<Pattern> patterns, AiAnalyzerStrategy delegate,
                                MessageSource messageSource) {
        this.patterns = List.copyOf(patterns);
        this.delegate = delegate;
        this.messageSource = messageSource;
    }

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                                    String costEstimateContext, String language, UUID aiConfigId) {
        guard(sql);
        return delegate.analyze(sql, dbType, schemaContext, costEstimateContext, language,
                aiConfigId);
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        guard(prompt);
        return delegate.generateSql(prompt, dbType, schemaContext, language, aiConfigId);
    }

    private void guard(String input) {
        if (input == null || patterns.isEmpty()) {
            return;
        }
        for (var pattern : patterns) {
            if (pattern.matcher(input).find()) {
                log.warn("AI guardrail blocked input matching pattern /{}/", pattern.pattern());
                var msg = messageSource.getMessage("error.ai.guardrail_blocked", null, currentLocale());
                throw new AiGuardrailViolationException(msg, pattern.pattern());
            }
        }
    }

    private Locale currentLocale() {
        var locale = LocaleContextHolder.getLocale();
        return locale != null ? locale : Locale.ENGLISH;
    }
}
