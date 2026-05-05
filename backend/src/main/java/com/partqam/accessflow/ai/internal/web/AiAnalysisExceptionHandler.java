package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(basePackageClasses = AiAnalysisExceptionHandler.class)
class AiAnalysisExceptionHandler {

    @ExceptionHandler(AiAnalysisException.class)
    ProblemDetail handleAiAnalysis(AiAnalysisException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setProperty("error", "AI_PROVIDER_UNAVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(AiAnalysisParseException.class)
    ProblemDetail handleAiAnalysisParse(AiAnalysisParseException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "AI_RESPONSE_INVALID");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
