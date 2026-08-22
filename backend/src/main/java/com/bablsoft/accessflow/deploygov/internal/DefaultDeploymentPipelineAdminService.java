package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanNotFoundException;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultDeploymentPipelineAdminService implements DeploymentPipelineAdminService {

    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final ReviewPlanLookupService reviewPlanLookupService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentPipelineView> list(UUID organizationId, PageRequest pageRequest) {
        var page = pipelineRepository.findByOrganizationId(organizationId, toPageable(pageRequest));
        return toPageResponse(page.map(DefaultDeploymentPipelineAdminService::toView));
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentPipelineView get(UUID id, UUID organizationId) {
        return toView(require(id, organizationId));
    }

    @Override
    @Transactional
    public DeploymentPipelineView create(CreateDeploymentPipelineCommand command) {
        if (pipelineRepository.existsByOrganizationIdAndName(command.organizationId(), command.name())) {
            throw new DuplicateDeploymentPipelineNameException(command.name());
        }
        requireReviewPlanInOrganization(command.reviewPlanId(), command.organizationId());
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setName(command.name());
        entity.setProvider(command.provider());
        entity.setRepositoryUrl(command.repositoryUrl());
        entity.setProjectRef(command.projectRef());
        entity.setReviewPlanId(command.reviewPlanId());
        entity.setAiAnalysisEnabled(command.aiAnalysisEnabled() == null || command.aiAnalysisEnabled());
        entity.setAiConfigId(command.aiConfigId());
        entity.setActive(true);
        return toView(pipelineRepository.save(entity));
    }

    @Override
    @Transactional
    public DeploymentPipelineView update(UUID id, UUID organizationId,
                                         UpdateDeploymentPipelineCommand command) {
        var entity = require(id, organizationId);
        if (command.name() != null && !command.name().equals(entity.getName())) {
            if (pipelineRepository.existsByOrganizationIdAndName(organizationId, command.name())) {
                throw new DuplicateDeploymentPipelineNameException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.provider() != null) {
            entity.setProvider(command.provider());
        }
        if (command.repositoryUrl() != null) {
            entity.setRepositoryUrl(command.repositoryUrl());
        }
        if (command.projectRef() != null) {
            entity.setProjectRef(command.projectRef());
        }
        if (Boolean.TRUE.equals(command.clearReviewPlan())) {
            entity.setReviewPlanId(null);
        } else if (command.reviewPlanId() != null) {
            requireReviewPlanInOrganization(command.reviewPlanId(), organizationId);
            entity.setReviewPlanId(command.reviewPlanId());
        }
        if (command.aiAnalysisEnabled() != null) {
            entity.setAiAnalysisEnabled(command.aiAnalysisEnabled());
        }
        if (Boolean.TRUE.equals(command.clearAiConfig())) {
            entity.setAiConfigId(null);
        } else if (command.aiConfigId() != null) {
            entity.setAiConfigId(command.aiConfigId());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        return toView(pipelineRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = require(id, organizationId);
        // Environments, freeze windows, and permission grants cascade at the database level.
        pipelineRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentEnvironmentView> listEnvironments(UUID pipelineId, UUID organizationId) {
        require(pipelineId, organizationId);
        return environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId).stream()
                .map(DefaultDeploymentPipelineAdminService::toEnvironmentView)
                .toList();
    }

    @Override
    @Transactional
    public DeploymentEnvironmentView createEnvironment(UUID pipelineId, UUID organizationId,
                                                       CreateDeploymentEnvironmentCommand command) {
        require(pipelineId, organizationId);
        if (environmentRepository.existsByPipelineIdAndName(pipelineId, command.name())) {
            throw new DuplicateDeploymentEnvironmentNameException(command.name());
        }
        requireReviewPlanInOrganization(command.reviewPlanId(), organizationId);
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipelineId);
        entity.setName(command.name());
        entity.setSortOrder(command.sortOrder() != null ? command.sortOrder() : 0);
        entity.setRequireReview(command.requireReview() == null || command.requireReview());
        entity.setRequiredApprovals(command.requiredApprovals());
        entity.setReviewPlanId(command.reviewPlanId());
        entity.setAllowBreakGlass(Boolean.TRUE.equals(command.allowBreakGlass()));
        return toEnvironmentView(environmentRepository.save(entity));
    }

    @Override
    @Transactional
    public DeploymentEnvironmentView updateEnvironment(UUID pipelineId, UUID organizationId,
                                                       UUID environmentId,
                                                       UpdateDeploymentEnvironmentCommand command) {
        require(pipelineId, organizationId);
        var entity = requireEnvironment(pipelineId, environmentId);
        if (command.name() != null && !command.name().equals(entity.getName())) {
            if (environmentRepository.existsByPipelineIdAndName(pipelineId, command.name())) {
                throw new DuplicateDeploymentEnvironmentNameException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.sortOrder() != null) {
            entity.setSortOrder(command.sortOrder());
        }
        if (command.requireReview() != null) {
            entity.setRequireReview(command.requireReview());
        }
        if (Boolean.TRUE.equals(command.clearRequiredApprovals())) {
            entity.setRequiredApprovals(null);
        } else if (command.requiredApprovals() != null) {
            entity.setRequiredApprovals(command.requiredApprovals());
        }
        if (Boolean.TRUE.equals(command.clearReviewPlan())) {
            entity.setReviewPlanId(null);
        } else if (command.reviewPlanId() != null) {
            requireReviewPlanInOrganization(command.reviewPlanId(), organizationId);
            entity.setReviewPlanId(command.reviewPlanId());
        }
        if (command.allowBreakGlass() != null) {
            entity.setAllowBreakGlass(command.allowBreakGlass());
        }
        return toEnvironmentView(environmentRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteEnvironment(UUID pipelineId, UUID organizationId, UUID environmentId) {
        require(pipelineId, organizationId);
        environmentRepository.delete(requireEnvironment(pipelineId, environmentId));
    }

    private DeploymentPipelineEntity require(UUID id, UUID organizationId) {
        return pipelineRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(id));
    }

    private DeploymentEnvironmentEntity requireEnvironment(UUID pipelineId, UUID environmentId) {
        return environmentRepository.findById(environmentId)
                .filter(e -> e.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(environmentId));
    }

    /** A cross-org plan id must read as "not found" — never reveal that the id exists elsewhere. */
    private void requireReviewPlanInOrganization(UUID reviewPlanId, UUID organizationId) {
        if (reviewPlanId == null) {
            return;
        }
        reviewPlanLookupService.findById(reviewPlanId)
                .filter(plan -> organizationId.equals(plan.organizationId()))
                .orElseThrow(() -> new ReviewPlanNotFoundException(reviewPlanId));
    }

    private static DeploymentPipelineView toView(DeploymentPipelineEntity e) {
        return new DeploymentPipelineView(
                e.getId(), e.getOrganizationId(), e.getName(), e.getProvider(),
                e.getRepositoryUrl(), e.getProjectRef(), e.getReviewPlanId(),
                e.isAiAnalysisEnabled(), e.getAiConfigId(), e.isActive(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private static DeploymentEnvironmentView toEnvironmentView(DeploymentEnvironmentEntity e) {
        return new DeploymentEnvironmentView(
                e.getId(), e.getPipelineId(), e.getName(), e.getSortOrder(), e.isRequireReview(),
                e.getRequiredApprovals(), e.getReviewPlanId(), e.isAllowBreakGlass(), e.getCreatedAt());
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
