package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNoDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftScanContextResolverTest {

    @Mock DeploymentEnvironmentLookupService environmentLookupService;
    @Mock DeploymentPipelineLookupService pipelineLookupService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock SchemaDriftConfigRepository configRepository;

    private SchemaDriftScanContextResolver resolver;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new SchemaDriftScanContextResolver(environmentLookupService, pipelineLookupService,
                datasourceAdminService, configRepository);
    }

    private void stubEnvironment(UUID boundDatasourceId) {
        when(environmentLookupService.findById(environmentId)).thenReturn(Optional.of(
                new DeploymentEnvironmentView(environmentId, pipelineId, "prod", 20, false, null, null,
                        false, Instant.EPOCH, List.of(), boundDatasourceId)));
    }

    private void stubPipelineVisible() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(DeploymentPipelineView.class)));
    }

    private void stubDatasource() {
        var view = org.mockito.Mockito.mock(DatasourceView.class);
        lenient().when(view.id()).thenReturn(datasourceId);
        lenient().when(view.dbType()).thenReturn(DbType.POSTGRESQL);
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view);
    }

    @Test
    void resolvesTheConfiguredBaselineOfTheOwningPipeline() {
        stubEnvironment(datasourceId);
        stubPipelineVisible();
        stubDatasource();
        var config = new SchemaDriftConfigEntity();
        config.setBaseline(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(config));

        assertThat(resolver.resolve(orgId, environmentId)).satisfies(ctx -> {
            assertThat(ctx.pipelineId()).isEqualTo(pipelineId);
            assertThat(ctx.datasourceId()).isEqualTo(datasourceId);
            assertThat(ctx.baseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        });
    }

    @Test
    void anUnconfiguredPipelineStillResolvesWithTheDefaultBaseline() {
        stubEnvironment(datasourceId);
        stubPipelineVisible();
        stubDatasource();
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());

        // The configuration decides what the scheduler does, not whether drift can be measured.
        assertThat(resolver.resolve(orgId, environmentId).baseline())
                .isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
    }

    @Test
    void anUnknownEnvironmentIsNotFound() {
        when(environmentLookupService.findById(environmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(orgId, environmentId))
                .isInstanceOf(SchemaChangeEnvironmentNotFoundException.class);
        verify(datasourceAdminService, never()).getForAdmin(any(), any());
    }

    @Test
    void anEnvironmentOnAnotherOrganizationsPipelineReadsAsNotFound() {
        stubEnvironment(datasourceId);
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.empty());

        // 404-never-403: the caller cannot tell "not yours" from "does not exist".
        assertThatThrownBy(() -> resolver.resolve(orgId, environmentId))
                .isInstanceOf(SchemaChangeEnvironmentNotFoundException.class);
        verify(datasourceAdminService, never()).getForAdmin(any(), any());
    }

    @Test
    void aDeployOnlyEnvironmentIsUnprocessable() {
        stubEnvironment(null);
        stubPipelineVisible();

        assertThatThrownBy(() -> resolver.resolve(orgId, environmentId))
                .isInstanceOf(SchemaChangeEnvironmentNoDatasourceException.class);
    }
}
