package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.SetupProgressView;

record AdminSetupProgressResponse(
        boolean datasourcesConfigured,
        boolean reviewPlansConfigured,
        boolean aiProviderConfigured,
        boolean governsApis,
        boolean apiConnectorsConfigured,
        boolean governsDeployments,
        boolean deploymentPipelinesConfigured,
        int completedSteps,
        int totalSteps,
        boolean complete) {

    static AdminSetupProgressResponse from(SetupProgressView view) {
        return new AdminSetupProgressResponse(
                view.datasourcesConfigured(),
                view.reviewPlansConfigured(),
                view.aiProviderConfigured(),
                view.governsApis(),
                view.apiConnectorsConfigured(),
                view.governsDeployments(),
                view.deploymentPipelinesConfigured(),
                view.completedSteps(),
                view.totalSteps(),
                view.complete());
    }
}
