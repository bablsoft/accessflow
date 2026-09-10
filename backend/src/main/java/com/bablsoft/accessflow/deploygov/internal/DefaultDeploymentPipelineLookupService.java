package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultDeploymentPipelineLookupService implements DeploymentPipelineLookupService {

    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;

    @Override
    public boolean hasAnyPipeline(UUID organizationId) {
        return pipelineRepository.existsByOrganizationId(organizationId);
    }

    @Override
    public Optional<DeploymentPipelineView> findPipeline(UUID pipelineId, UUID organizationId) {
        return pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId)
                .map(DefaultDeploymentPipelineLookupService::toView);
    }

    @Override
    public Optional<DeploymentEnvironmentView> findEnvironment(UUID pipelineId, UUID environmentId) {
        return environmentRepository.findById(environmentId)
                .filter(e -> e.getPipelineId().equals(pipelineId))
                .map(DefaultDeploymentPipelineLookupService::toView);
    }

    @Override
    public List<DeploymentEnvironmentView> listEnvironments(UUID pipelineId) {
        return environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId).stream()
                .map(DefaultDeploymentPipelineLookupService::toView)
                .toList();
    }

    private static DeploymentPipelineView toView(DeploymentPipelineEntity e) {
        return new DeploymentPipelineView(e.getId(), e.getOrganizationId(), e.getName(),
                e.getProvider(), e.getRepositoryUrl(), e.getProjectRef(), e.getReviewPlanId(),
                e.isAiAnalysisEnabled(), e.getAiConfigId(), e.isActive(), e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private static DeploymentEnvironmentView toView(DeploymentEnvironmentEntity e) {
        return new DeploymentEnvironmentView(e.getId(), e.getPipelineId(), e.getName(),
                e.getSortOrder(), e.isRequireReview(), e.getRequiredApprovals(),
                e.getReviewPlanId(), e.isAllowBreakGlass(), e.getCreatedAt(),
                e.getTags() == null ? List.of() : List.of(e.getTags()));
    }
}
