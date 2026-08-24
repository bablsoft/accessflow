package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.DeploymentAnalyzer;
import com.bablsoft.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link DeploymentAnalyzer}: enforces the per-org AI guardrails, then frames the release
 * metadata and delegates to the provider {@link AiAnalyzerStrategy}. The framed text is passed as
 * the analyzer's primary input; {@link DbType#CUSTOM} marks the non-SQL path so adapters do not
 * assume a SQL dialect.
 */
@Service
@RequiredArgsConstructor
class DefaultDeploymentAnalyzer implements DeploymentAnalyzer {

    private final AiAnalyzerStrategy strategy;
    private final AiRateLimiter rateLimiter;

    @Override
    public AiAnalysisResult analyzeDeployment(DeploymentAnalysisInput input) {
        rateLimiter.enforce(input.organizationId());
        var framed = """
                Analyze the risk of the following software deployment awaiting approval in a
                deployment governance system.
                CI/CD provider: %s
                Target environment: %s
                Artifact version: %s
                Commit: %s
                Artifact reference: %s
                Release metadata (changelog, commit list, diff summary):
                %s
                Treat schema or data migrations, changes to authentication, authorization, payment or
                secret handling, deletion of production resources, large or unreviewed diffs, and
                deployments straight to a production-like environment as elevated risk."""
                .formatted(safe(input.provider()), safe(input.environment()), safe(input.version()),
                        blankToNone(input.commitSha()), blankToNone(input.artifactRef()),
                        blankToNone(input.metadataContext()));
        // The metadata is already inside `framed`; passing it again as the schema context would
        // duplicate the largest part of the prompt and bill the org's token budget twice. A
        // deployment has no schema, so that slot stays empty.
        return strategy.analyze(framed, DbType.CUSTOM, null, input.language(), input.aiConfigId());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }
}
