package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.ai.api.AiConfigLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSetupProgressServiceTest {

    @Mock OrganizationSetupLookupService organizationSetupLookupService;
    @Mock AiConfigLookupService aiConfigLookupService;
    @Mock ApiConnectorLookupService apiConnectorLookupService;
    @Mock DeploymentPipelineLookupService deploymentPipelineLookupService;
    @InjectMocks DefaultSetupProgressService service;

    private final UUID orgId = UUID.randomUUID();

    private void stubBaseSteps(boolean datasource, boolean reviewPlan, boolean ai) {
        when(organizationSetupLookupService.hasAnyDatasource(orgId)).thenReturn(datasource);
        when(organizationSetupLookupService.hasAnyReviewPlan(orgId)).thenReturn(reviewPlan);
        when(aiConfigLookupService.hasAnyUsableAiConfig(orgId)).thenReturn(ai);
    }

    private void stubDomains(boolean governsApis, boolean governsDeployments) {
        when(organizationSetupLookupService.governsApis(orgId)).thenReturn(governsApis);
        when(organizationSetupLookupService.governsDeployments(orgId)).thenReturn(governsDeployments);
    }

    @Test
    void reportsNothingConfiguredOnFreshInstall() {
        stubBaseSteps(false, false, false);
        stubDomains(false, false);

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
        stubBaseSteps(true, false, false);
        stubDomains(false, false);

        var view = service.getProgress(orgId);

        assertThat(view.datasourcesConfigured()).isTrue();
        assertThat(view.reviewPlansConfigured()).isFalse();
        assertThat(view.aiProviderConfigured()).isFalse();
        assertThat(view.completedSteps()).isEqualTo(1);
        assertThat(view.complete()).isFalse();
    }

    @Test
    void countsOnlyReviewPlanWhenItIsTheSingleConfiguredStep() {
        stubBaseSteps(false, true, false);
        stubDomains(false, false);

        var view = service.getProgress(orgId);

        assertThat(view.reviewPlansConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(1);
    }

    @Test
    void countsAiProviderConfiguredWhenLookupReportsAnyUsableAiConfig() {
        stubBaseSteps(false, false, true);
        stubDomains(false, false);

        var view = service.getProgress(orgId);

        assertThat(view.aiProviderConfigured()).isTrue();
        assertThat(view.completedSteps()).isEqualTo(1);
    }

    @Test
    void reportsCompleteWhenAllThreeBaseStepsSatisfiedAndNoDomainGoverned() {
        stubBaseSteps(true, true, true);
        stubDomains(false, false);

        var view = service.getProgress(orgId);

        assertThat(view.governsApis()).isFalse();
        assertThat(view.governsDeployments()).isFalse();
        assertThat(view.apiConnectorsConfigured()).isFalse();
        assertThat(view.deploymentPipelinesConfigured()).isFalse();
        assertThat(view.completedSteps()).isEqualTo(3);
        assertThat(view.totalSteps()).isEqualTo(3);
        assertThat(view.complete()).isTrue();
    }

    @Test
    void skipsTheModuleLookupsEntirelyWhenNeitherDomainIsGoverned() {
        stubBaseSteps(true, true, true);
        stubDomains(false, false);

        service.getProgress(orgId);

        verify(apiConnectorLookupService, never()).hasAnyConnector(orgId);
        verify(deploymentPipelineLookupService, never()).hasAnyPipeline(orgId);
    }

    @Test
    void addsOnlyTheApiStepWhenOnlyApisAreGoverned() {
        stubBaseSteps(true, true, true);
        stubDomains(true, false);
        when(apiConnectorLookupService.hasAnyConnector(orgId)).thenReturn(false);

        var view = service.getProgress(orgId);

        assertThat(view.governsApis()).isTrue();
        assertThat(view.apiConnectorsConfigured()).isFalse();
        assertThat(view.totalSteps()).isEqualTo(4);
        assertThat(view.completedSteps()).isEqualTo(3);
        assertThat(view.complete()).isFalse();
    }

    @Test
    void flipsCompleteOnceTheGovernedApiStepIsSatisfied() {
        stubBaseSteps(true, true, true);
        stubDomains(true, false);
        when(apiConnectorLookupService.hasAnyConnector(orgId)).thenReturn(true);

        var view = service.getProgress(orgId);

        assertThat(view.apiConnectorsConfigured()).isTrue();
        assertThat(view.totalSteps()).isEqualTo(4);
        assertThat(view.completedSteps()).isEqualTo(4);
        assertThat(view.complete()).isTrue();
    }

    @Test
    void addsOnlyTheDeploymentStepWhenOnlyDeploymentsAreGoverned() {
        stubBaseSteps(true, true, true);
        stubDomains(false, true);
        when(deploymentPipelineLookupService.hasAnyPipeline(orgId)).thenReturn(false);

        var view = service.getProgress(orgId);

        assertThat(view.governsDeployments()).isTrue();
        assertThat(view.deploymentPipelinesConfigured()).isFalse();
        assertThat(view.totalSteps()).isEqualTo(4);
        assertThat(view.completedSteps()).isEqualTo(3);
        assertThat(view.complete()).isFalse();
        verify(apiConnectorLookupService, never()).hasAnyConnector(orgId);
    }

    @Test
    void addsBothStepsWhenBothDomainsAreGoverned() {
        stubBaseSteps(true, true, true);
        stubDomains(true, true);
        when(apiConnectorLookupService.hasAnyConnector(orgId)).thenReturn(true);
        when(deploymentPipelineLookupService.hasAnyPipeline(orgId)).thenReturn(false);

        var view = service.getProgress(orgId);

        assertThat(view.apiConnectorsConfigured()).isTrue();
        assertThat(view.deploymentPipelinesConfigured()).isFalse();
        assertThat(view.totalSteps()).isEqualTo(5);
        assertThat(view.completedSteps()).isEqualTo(4);
        assertThat(view.complete()).isFalse();
    }

    @Test
    void reportsCompleteWhenBothGovernedDomainsAreSatisfied() {
        stubBaseSteps(true, true, true);
        stubDomains(true, true);
        when(apiConnectorLookupService.hasAnyConnector(orgId)).thenReturn(true);
        when(deploymentPipelineLookupService.hasAnyPipeline(orgId)).thenReturn(true);

        var view = service.getProgress(orgId);

        assertThat(view.totalSteps()).isEqualTo(5);
        assertThat(view.completedSteps()).isEqualTo(5);
        assertThat(view.complete()).isTrue();
    }
}
