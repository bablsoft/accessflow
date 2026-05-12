package com.partqam.accessflow.api.internal;

import com.partqam.accessflow.ai.api.AiConfigLookupService;
import com.partqam.accessflow.core.api.OrganizationSetupLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultSetupProgressService implements SetupProgressService {

    private static final int TOTAL_STEPS = 3;

    private final OrganizationSetupLookupService organizationSetupLookupService;
    private final AiConfigLookupService aiConfigLookupService;

    @Override
    @Transactional(readOnly = true)
    public SetupProgressView getProgress(UUID organizationId) {
        var hasDatasource = organizationSetupLookupService.hasAnyDatasource(organizationId);
        var hasReviewPlan = organizationSetupLookupService.hasAnyReviewPlan(organizationId);
        var aiConfigured = aiConfigLookupService.hasAnyUsableAiConfig(organizationId);
        var completed = (hasDatasource ? 1 : 0) + (hasReviewPlan ? 1 : 0) + (aiConfigured ? 1 : 0);
        return new SetupProgressView(
                hasDatasource,
                hasReviewPlan,
                aiConfigured,
                completed,
                TOTAL_STEPS,
                completed == TOTAL_STEPS);
    }
}
