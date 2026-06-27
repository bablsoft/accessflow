package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.List;
import java.util.UUID;

/**
 * Editor-time AI assistance for API calls: a debounced risk preview and natural-language text-to-API
 * generation. Text-to-API is only available for connectors that have a parsed schema and
 * {@code text_to_api_enabled}.
 */
public interface ApiAssistService {

    ApiAiPreview analyze(UUID connectorId, UUID organizationId, UUID userId, boolean admin,
                         AnalyzeInput input);

    GeneratedApiCallView generate(UUID connectorId, UUID organizationId, UUID userId, boolean admin,
                                  String prompt, String language);

    record AnalyzeInput(String operationId, String verb, String requestPath, String requestBody,
                        String language) {
    }

    record ApiAiPreview(int riskScore, RiskLevel riskLevel, String summary, List<String> issues) {
    }

    record GeneratedApiCallView(String draft) {
    }
}
