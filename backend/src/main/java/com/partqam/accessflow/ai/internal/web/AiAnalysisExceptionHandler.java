package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiConfigInUseException;
import com.partqam.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.partqam.accessflow.ai.api.AiConfigNotFoundException;
import lombok.RequiredArgsConstructor;
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

    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(AiAnalysisException.class)
    ProblemDetail handleAiAnalysis(AiAnalysisException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, msg("error.ai_provider_unavailable"));
        pd.setProperty("error", "AI_PROVIDER_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiAnalysisParseException.class)
    ProblemDetail handleAiAnalysisParse(AiAnalysisParseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, msg("error.ai_response_invalid"));
        pd.setProperty("error", "AI_RESPONSE_INVALID");
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
