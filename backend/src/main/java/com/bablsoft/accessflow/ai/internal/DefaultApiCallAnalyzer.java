package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.ApiCallAnalyzer;
import com.bablsoft.accessflow.ai.api.GeneratedApiCall;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link ApiCallAnalyzer}: enforces the per-org AI guardrails, then frames the API call (or
 * the text-to-API prompt) and delegates to the provider {@link AiAnalyzerStrategy}. The framed text
 * is passed as the analyzer's primary input; {@link DbType#CUSTOM} marks the non-SQL path so adapters
 * do not assume a SQL dialect.
 */
@Service
@RequiredArgsConstructor
class DefaultApiCallAnalyzer implements ApiCallAnalyzer {

    private final AiAnalyzerStrategy strategy;
    private final AiRateLimiter rateLimiter;

    @Override
    public AiAnalysisResult analyzeApiCall(ApiCallAnalysisInput input) {
        rateLimiter.enforce(input.organizationId());
        var framed = """
                Analyze the risk of the following outbound API call made through a governed API proxy.
                Protocol: %s
                Method/operation: %s
                Path: %s
                Request body:
                %s
                Treat data exfiltration, destructive or bulk-mutating operations, calls to sensitive
                endpoints, and missing scoping as elevated risk.""".formatted(
                safe(input.protocol()), safe(input.verb()), safe(input.path()),
                input.requestBody() == null ? "(none)" : input.requestBody());
        return strategy.analyze(framed, DbType.CUSTOM, input.schemaContext(), input.language(),
                input.aiConfigId());
    }

    @Override
    public GeneratedApiCall generateApiCall(ApiCallGenerationInput input) {
        rateLimiter.enforce(input.organizationId());
        var framed = """
                You are drafting a single outbound API call for a %s API. Using ONLY the operations in
                the provided schema, produce the concrete call (method/operation, path, and JSON request
                body if needed) that satisfies this request. Return only the call, no prose:
                %s""".formatted(safe(input.protocol()), safe(input.prompt()));
        GeneratedSqlResult result = strategy.generateSql(framed, DbType.CUSTOM, input.schemaContext(),
                input.language(), input.aiConfigId());
        return new GeneratedApiCall(result.sql(), result.aiProvider(), result.aiModel(),
                result.promptTokens(), result.completionTokens());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
