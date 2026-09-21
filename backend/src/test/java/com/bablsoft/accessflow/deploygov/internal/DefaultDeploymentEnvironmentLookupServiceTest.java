package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentEnvironmentLookupServiceTest {

    @Mock
    private DeploymentEnvironmentRepository environmentRepository;

    @InjectMocks
    private DefaultDeploymentEnvironmentLookupService service;

    private final UUID pipelineId = UUID.randomUUID();

    @Test
    void listByPipelineReturnsTheLadderInRepositoryOrderWithDatasources() {
        var datasourceId = UUID.randomUUID();
        var dev = environment("dev", 0, null);
        var prod = environment("production", 1, datasourceId);
        when(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId))
                .thenReturn(List.of(dev, prod));

        var ladder = service.listByPipeline(pipelineId);

        assertThat(ladder).extracting(v -> v.name()).containsExactly("dev", "production");
        assertThat(ladder).extracting(v -> v.sortOrder()).containsExactly(0, 1);
        assertThat(ladder).extracting(v -> v.datasourceId()).containsExactly(null, datasourceId);
        assertThat(ladder.get(1).pipelineId()).isEqualTo(pipelineId);
    }

    @Test
    void listByPipelineIsEmptyForAnUnknownPipeline() {
        when(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId))
                .thenReturn(List.of());

        assertThat(service.listByPipeline(pipelineId)).isEmpty();
    }

    @Test
    void findByIdMapsTheEnvironment() {
        var datasourceId = UUID.randomUUID();
        var staging = environment("staging", 2, datasourceId);
        when(environmentRepository.findById(staging.getId())).thenReturn(Optional.of(staging));

        var view = service.findById(staging.getId()).orElseThrow();

        assertThat(view.id()).isEqualTo(staging.getId());
        assertThat(view.pipelineId()).isEqualTo(pipelineId);
        assertThat(view.sortOrder()).isEqualTo(2);
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
    }

    @Test
    void findByIdIsEmptyWhenMissing() {
        var id = UUID.randomUUID();
        when(environmentRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }

    @Test
    void findByIdNullGuardSkipsTheRepository() {
        assertThat(service.findById(null)).isEmpty();
        verify(environmentRepository, never()).findById(any());
    }

    private DeploymentEnvironmentEntity environment(String name, int sortOrder, UUID datasourceId) {
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(pipelineId);
        e.setName(name);
        e.setSortOrder(sortOrder);
        e.setDatasourceId(datasourceId);
        return e;
    }
}
