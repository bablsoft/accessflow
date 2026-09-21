package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The #877 promotion-ladder lookup — a thin read over the environment repository. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultDeploymentEnvironmentLookupService implements DeploymentEnvironmentLookupService {

    private final DeploymentEnvironmentRepository environmentRepository;

    @Override
    public List<DeploymentEnvironmentView> listByPipeline(UUID pipelineId) {
        return environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId).stream()
                .map(DeploymentEnvironmentViewMapper::toView)
                .toList();
    }

    @Override
    public Optional<DeploymentEnvironmentView> findById(UUID environmentId) {
        if (environmentId == null) {
            return Optional.empty();
        }
        return environmentRepository.findById(environmentId).map(DeploymentEnvironmentViewMapper::toView);
    }
}
