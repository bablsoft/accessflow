package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

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
}
