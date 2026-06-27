package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * AI entry point for the API Access Governance module (AF-500). Wraps the per-org rate limiter and
 * the provider {@code AiAnalyzerStrategy} so the {@code apigov} module depends only on this public
 * interface — never on AI internals. Implementations are async-safe and resolve the provider from
 * {@code aiConfigId}.
 */
public interface ApiCallAnalyzer {

    /**
     * Risk-score a submitted API call. The call (protocol/verb/path/body) and the operation
     * {@code schemaContext} are framed into the analyzer prompt; the structured
     * {@link AiAnalysisResult} (risk score/level, issues) is returned.
     *
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisException provider call failed / not configured
     *                                                            or a guardrail limit was exceeded
     */
    AiAnalysisResult analyzeApiCall(ApiCallAnalysisInput input);

    /**
     * Turn a natural-language request into a concrete API-call draft. Only meaningful when the
     * connector has a parsed schema (passed as {@code schemaContext}); the draft still flows through
     * the full review pipeline.
     *
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisException provider call failed / not configured
     */
    GeneratedApiCall generateApiCall(ApiCallGenerationInput input);

    /** Input to {@link #analyzeApiCall}. {@code protocol} is a free-form label (REST/SOAP/…). */
    record ApiCallAnalysisInput(
            UUID organizationId, UUID aiConfigId, String protocol, String verb, String path,
            String requestBody, String schemaContext, String language) {
    }

    /** Input to {@link #generateApiCall}. */
    record ApiCallGenerationInput(
            UUID organizationId, UUID aiConfigId, String protocol, String prompt, String schemaContext,
            String language) {
    }
}
