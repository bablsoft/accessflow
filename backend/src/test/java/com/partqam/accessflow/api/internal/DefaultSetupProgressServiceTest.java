package com.partqam.accessflow.api.internal;

import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.ai.api.AiConfigView;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.OrganizationSetupLookupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSetupProgressServiceTest {

    @Mock OrganizationSetupLookupService organizationSetupLookupService;
    @Mock AiConfigService aiConfigService;
    @InjectMocks DefaultSetupProgressService service;

    @Test
    void reportsNothingConfiguredOnFreshInstall() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(false);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(false);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.ANTHROPIC, false));

        var view = service.getProgress(orgId);

        assertThat(view.datasourcesConfigured()).isFalse();
        assertThat(view.reviewPlansConfigured()).isFalse();
        assertThat(view.aiProviderConfigured()).isFalse();
        assertThat(view.completedSteps()).isEqualTo(0);
        assertThat(view.totalSteps()).isEqualTo(3);
        assertThat(view.complete()).isFalse();
    }

    @Test
    void countsOnlyDatasourceWhenItIsTheSingleConfiguredStep() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(true);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(false);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.OPENAI, false));

        var view = service.getProgress(orgId);

        assertThat(view.datasourcesConfigured()).isTrue();
        assertThat(view.reviewPlansConfigured()).isFalse();
        assertThat(view.aiProviderConfigured()).isFalse();
        assertThat(view.completedSteps()).isEqualTo(1);
        assertThat(view.complete()).isFalse();
    }

    @Test
    void countsOnlyReviewPlanWhenItIsTheSingleConfiguredStep() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(false);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(true);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.ANTHROPIC, false));

        var view = service.getProgress(orgId);

        assertThat(view.reviewPlansConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(1);
    }

    @Test
    void countsAiProviderConfiguredWhenApiKeyPresent() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(false);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(false);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.ANTHROPIC, true));

        var view = service.getProgress(orgId);

        assertThat(view.aiProviderConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(1);
    }

    @Test
    void treatsOllamaWithoutApiKeyAsConfigured() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(false);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(false);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.OLLAMA, false));

        var view = service.getProgress(orgId);

        assertThat(view.aiProviderConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(1);
    }

    @Test
    void reportsCompleteWhenAllThreeStepsSatisfied() {
        var orgId = UUID.randomUUID();
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(true);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(true);
        when(aiConfigService.getOrDefault(orgId))
                .thenReturn(aiView(AiProviderType.ANTHROPIC, true));

        var view = service.getProgress(orgId);

        assertThat(view.datasourcesConfigured()).isTrue();
        assertThat(view.reviewPlansConfigured()).isTrue();
        assertThat(view.aiProviderConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(3);
        assertThat(view.totalSteps()).isEqualTo(3);
        assertThat(view.complete()).isTrue();
    }

    private static AiConfigView aiView(AiProviderType provider, boolean apiKeyMasked) {
        var now = Instant.now();
        return new AiConfigView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                provider,
                "model",
                null,
                apiKeyMasked,
                30_000,
                8_000,
                2_000,
                true,
                false,
                true,
                true,
                now,
                now);
    }
}
