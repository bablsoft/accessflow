package com.partqam.accessflow.api.internal.web;

import com.partqam.accessflow.api.internal.SetupProgressView;

record AdminSetupProgressResponse(
        boolean datasourcesConfigured,
        boolean reviewPlansConfigured,
        boolean aiProviderConfigured,
        int completedSteps,
        int totalSteps,
        boolean complete) {

    static AdminSetupProgressResponse from(SetupProgressView view) {
        return new AdminSetupProgressResponse(
                view.datasourcesConfigured(),
                view.reviewPlansConfigured(),
                view.aiProviderConfigured(),
                view.completedSteps(),
                view.totalSteps(),
                view.complete());
    }
}
