package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentPipelineLookupServiceTest {

    @Mock DeploymentPipelineRepository pipelineRepository;
    @InjectMocks DefaultDeploymentPipelineLookupService service;

    private final UUID organizationId = UUID.randomUUID();

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
}
