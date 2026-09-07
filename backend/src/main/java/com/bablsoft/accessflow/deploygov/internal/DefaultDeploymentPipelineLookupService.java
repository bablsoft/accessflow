package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultDeploymentPipelineLookupService implements DeploymentPipelineLookupService {

    private final DeploymentPipelineRepository pipelineRepository;

    @Override
    public boolean hasAnyPipeline(UUID organizationId) {
        return pipelineRepository.existsByOrganizationId(organizationId);
    }
}
