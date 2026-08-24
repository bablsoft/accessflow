package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * AI entry point for the deployment-governance module (#691, epic #682). Sibling of
 * {@link ApiCallAnalyzer}: wraps the per-org guardrails and the provider {@code AiAnalyzerStrategy}
 * so the {@code deploygov} module depends only on this public interface — never on AI internals.
 *
 * <p>The event listener that reacts to a submitted deployment lives in {@code deploygov}, not here,
 * so this module never depends on {@code deploygov}.
 */
public interface DeploymentAnalyzer {

    /**
     * Risk-score a submitted deployment from its release metadata. The target environment, provider,
     * artifact version and the rendered {@code metadataContext} (changelog, commit list, diff
     * summary) are framed into the analyzer prompt; the structured {@link AiAnalysisResult} is
     * returned.
     *
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisException      provider call failed / not
     *                                                                configured, or a guardrail
     *                                                                limit was exceeded
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisParseException the provider response was not
     *                                                                the expected strict JSON
     */
    AiAnalysisResult analyzeDeployment(DeploymentAnalysisInput input);

    /**
     * Input to {@link #analyzeDeployment}. {@code provider} is the CI/CD provider label
     * (GITHUB_ACTIONS/GITLAB_CI/…); {@code metadataContext} is a free-form rendered release context
     * and may be {@code null}.
     */
    record DeploymentAnalysisInput(
            UUID organizationId, UUID aiConfigId, String provider, String environment, String version,
            String commitSha, String artifactRef, String metadataContext, String language) {
    }
}
