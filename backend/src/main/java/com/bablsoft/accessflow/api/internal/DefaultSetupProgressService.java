package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.ai.api.AiConfigLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultSetupProgressService implements SetupProgressService {

    /** Review plan, AI provider, datasource — database governance is always on. */
    private static final int BASE_STEPS = 3;

    private final OrganizationSetupLookupService organizationSetupLookupService;
    private final AiConfigLookupService aiConfigLookupService;
    private final ApiConnectorLookupService apiConnectorLookupService;
    private final DeploymentPipelineLookupService deploymentPipelineLookupService;

    @Override
    @Transactional(readOnly = true)
    public SetupProgressView getProgress(UUID organizationId) {
        var hasDatasource = organizationSetupLookupService.hasAnyDatasource(organizationId);
        var hasReviewPlan = organizationSetupLookupService.hasAnyReviewPlan(organizationId);
        var aiConfigured = aiConfigLookupService.hasAnyUsableAiConfig(organizationId);

        var governsApis = organizationSetupLookupService.governsApis(organizationId);
        var governsDeployments = organizationSetupLookupService.governsDeployments(organizationId);
        // Only ask the owning module when the domain is governed — an ungoverned domain contributes
        // no step, so its existence check would be a query nobody reads.
        var apiConnectorsConfigured =
                governsApis && apiConnectorLookupService.hasAnyConnector(organizationId);
        var deploymentPipelinesConfigured =
                governsDeployments && deploymentPipelineLookupService.hasAnyPipeline(organizationId);

        var totalSteps = BASE_STEPS + (governsApis ? 1 : 0) + (governsDeployments ? 1 : 0);
        var completed = (hasDatasource ? 1 : 0)
                + (hasReviewPlan ? 1 : 0)
                + (aiConfigured ? 1 : 0)
                + (apiConnectorsConfigured ? 1 : 0)
                + (deploymentPipelinesConfigured ? 1 : 0);

        return new SetupProgressView(
                hasDatasource,
                hasReviewPlan,
                aiConfigured,
                governsApis,
                apiConnectorsConfigured,
                governsDeployments,
                deploymentPipelinesConfigured,
                completed,
                totalSteps,
                completed == totalSteps);
    }
}
