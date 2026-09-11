package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentPipelineLookupServiceTest {

    @Mock DeploymentPipelineRepository pipelineRepository;
    @Mock DeploymentEnvironmentRepository environmentRepository;
    @InjectMocks DefaultDeploymentPipelineLookupService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @Test
    void hasAnyPipelineDelegatesToTheActiveAgnosticExistenceQuery() {
        when(pipelineRepository.existsByOrganizationId(organizationId)).thenReturn(true);

        assertThat(service.hasAnyPipeline(organizationId)).isTrue();
    }

    @Test
    void hasAnyPipelineReturnsFalseWhenTheOrganizationOwnsNone() {
        when(pipelineRepository.existsByOrganizationId(organizationId)).thenReturn(false);

        assertThat(service.hasAnyPipeline(organizationId)).isFalse();
    }

    // ── The decision-trace reads (AF-967) ─────────────────────────────────────

    private DeploymentPipelineEntity pipeline() {
        var entity = new DeploymentPipelineEntity();
        entity.setId(pipelineId);
        entity.setOrganizationId(organizationId);
        entity.setName("checkout-service");
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setActive(true);
        entity.setAiAnalysisEnabled(true);
        return entity;
    }

    private DeploymentEnvironmentEntity environment(UUID owningPipeline) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(environmentId);
        entity.setPipelineId(owningPipeline);
        entity.setName("production");
        entity.setSortOrder(1);
        entity.setRequireReview(true);
        entity.setRequiredApprovals(2);
        entity.setAllowBreakGlass(true);
        entity.setTags(new String[]{"prod"});
        return entity;
    }

    @Test
    void findPipelineMapsTheOrganizationScopedRow() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId))
                .thenReturn(Optional.of(pipeline()));

        var view = service.findPipeline(pipelineId, organizationId).orElseThrow();

        assertThat(view.id()).isEqualTo(pipelineId);
        assertThat(view.name()).isEqualTo("checkout-service");
        assertThat(view.provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
        assertThat(view.active()).isTrue();
        assertThat(view.aiAnalysisEnabled()).isTrue();
    }

    @Test
    void findPipelineIsEmptyForAnotherOrganization() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId))
                .thenReturn(Optional.empty());

        assertThat(service.findPipeline(pipelineId, organizationId)).isEmpty();
    }

    @Test
    void findEnvironmentMapsTheRowIncludingItsTags() {
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(pipelineId)));

        var view = service.findEnvironment(pipelineId, environmentId).orElseThrow();

        assertThat(view.name()).isEqualTo("production");
        assertThat(view.requireReview()).isTrue();
        assertThat(view.requiredApprovals()).isEqualTo(2);
        assertThat(view.allowBreakGlass()).isTrue();
        assertThat(view.tags()).containsExactly("prod");
    }

    @Test
    void findEnvironmentIsEmptyWhenItBelongsToAnotherPipeline() {
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(UUID.randomUUID())));

        assertThat(service.findEnvironment(pipelineId, environmentId)).isEmpty();
    }

    @Test
    void findEnvironmentIsEmptyWhenTheEnvironmentIsUnknown() {
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.empty());

        assertThat(service.findEnvironment(pipelineId, environmentId)).isEmpty();
    }

    @Test
    void anEnvironmentWithNullTagsMapsToAnEmptyList() {
        var entity = environment(pipelineId);
        entity.setTags(null);
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(entity));

        assertThat(service.findEnvironment(pipelineId, environmentId).orElseThrow().tags())
                .isEmpty();
    }
}
