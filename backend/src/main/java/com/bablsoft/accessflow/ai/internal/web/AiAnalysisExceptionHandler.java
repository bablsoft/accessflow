package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiConfigEndpointRequiredException;
import com.bablsoft.accessflow.ai.api.AiConfigInUseException;
import com.bablsoft.accessflow.ai.api.AiConfigInvalidPromptException;
import com.bablsoft.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentIngestException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentNotFoundException;
import com.bablsoft.accessflow.ai.api.TextToSqlDisabledException;
import com.bablsoft.accessflow.ai.api.TextToSqlNotConfiguredException;
import com.bablsoft.accessflow.ai.internal.BadAiAnalysisStatsQueryException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(basePackageClasses = AiAnalysisExceptionHandler.class)
@RequiredArgsConstructor
class AiAnalysisExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisExceptionHandler.class);

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(BadAiAnalysisStatsQueryException.class)
    ProblemDetail handleBadAiAnalysisStatsQuery(BadAiAnalysisStatsQueryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg(ex.getMessage()));
        pd.setProperty("error", "BAD_AI_ANALYSIS_STATS_QUERY");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiAnalysisException.class)
    ProblemDetail handleAiAnalysis(AiAnalysisException ex) {
        // Log the cause so operators can diagnose provider failures; the message may carry endpoint
        // detail, so it is never surfaced to the client.
        log.warn("AI provider call failed: {}", ex.getMessage(), ex);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, msg("error.ai_provider_unavailable"));
        pd.setProperty("error", "AI_PROVIDER_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiAnalysisParseException.class)
    ProblemDetail handleAiAnalysisParse(AiAnalysisParseException ex) {
        // Log the specific reason (which engine, what failed) and surface it as a diagnostic
        // `reason` property so the editor can tell the user why generation/analysis failed — the
        // localized `detail` stays generic.
        log.warn("AI response could not be parsed: {}", ex.getMessage(), ex);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.ai_response_invalid"));
        pd.setProperty("error", "AI_RESPONSE_INVALID");
        pd.setProperty("reason", ex.getMessage());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigNotFoundException.class)
    ProblemDetail handleAiConfigNotFound(AiConfigNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.ai_config.not_found"));
        pd.setProperty("error", "AI_CONFIG_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TextToSqlDisabledException.class)
    ProblemDetail handleTextToSqlDisabled(TextToSqlDisabledException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.text_to_sql.disabled"));
        pd.setProperty("error", "TEXT_TO_SQL_DISABLED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(TextToSqlNotConfiguredException.class)
    ProblemDetail handleTextToSqlNotConfigured(TextToSqlNotConfiguredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.text_to_sql.not_configured"));
        pd.setProperty("error", "TEXT_TO_SQL_NOT_CONFIGURED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigEndpointRequiredException.class)
    ProblemDetail handleAiConfigEndpointRequired(AiConfigEndpointRequiredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.ai_config.endpoint_required"));
        pd.setProperty("error", "AI_CONFIG_ENDPOINT_REQUIRED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigInvalidPromptException.class)
    ProblemDetail handleAiConfigInvalidPrompt(AiConfigInvalidPromptException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.ai_config.prompt_missing_sql_placeholder"));
        pd.setProperty("error", "AI_CONFIG_INVALID_PROMPT");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigRagInvalidException.class)
    ProblemDetail handleAiConfigRagInvalid(AiConfigRagInvalidException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg(ex.messageKey()));
        pd.setProperty("error", "RAG_CONFIG_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(KnowledgeDocumentNotFoundException.class)
    ProblemDetail handleKnowledgeDocumentNotFound(KnowledgeDocumentNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.knowledge_document.not_found"));
        pd.setProperty("error", "KNOWLEDGE_DOCUMENT_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(KnowledgeDocumentIngestException.class)
    ProblemDetail handleKnowledgeDocumentIngest(KnowledgeDocumentIngestException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                msg("error.ai_config.rag.ingest_failed"));
        pd.setProperty("error", "RAG_INGEST_FAILED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigNameAlreadyExistsException.class)
    ProblemDetail handleAiConfigNameAlreadyExists(AiConfigNameAlreadyExistsException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                msg("error.ai_config.name_already_exists"));
        pd.setProperty("error", "AI_CONFIG_NAME_ALREADY_EXISTS");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiConfigInUseException.class)
    ProblemDetail handleAiConfigInUse(AiConfigInUseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.ai_config.in_use"));
        pd.setProperty("error", "AI_CONFIG_IN_USE");
        pd.setProperty("timestamp", Instant.now().toString());
        var bound = ex.boundDatasources().stream()
                .map(ref -> Map.<String, Object>of("id", ref.id(), "name", ref.name()))
                .toList();
        pd.setProperty("boundDatasources", bound);
        return pd;
    }
}
