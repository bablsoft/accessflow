package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaselineEnvironmentInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConcurrentUpdateException;
import com.bablsoft.accessflow.schemachange.api.UpsertSchemaDriftConfigCommand;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaDriftConfigServiceTest {

    @Mock SchemaDriftConfigRepository configRepository;
    @Mock DeploymentPipelineLookupService pipelineLookupService;
    @Mock DeploymentEnvironmentLookupService environmentLookupService;
    @Mock SchemaChangeAuditWriter auditWriter;

    private DefaultSchemaDriftConfigService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaDriftConfigService(configRepository, pipelineLookupService,
                environmentLookupService, auditWriter);
        lenient().when(configRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void stubPipelineVisible() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(DeploymentPipelineView.class)));
    }

    private void stubEnvironment(UUID id, UUID owningPipelineId, UUID boundDatasourceId) {
        when(environmentLookupService.findById(id)).thenReturn(Optional.of(
                new DeploymentEnvironmentView(id, owningPipelineId, "dev", 10, false, null, null, false,
                        Instant.EPOCH, List.of(), boundDatasourceId)));
    }

    private UpsertSchemaDriftConfigCommand command(SchemaDriftBaseline baseline, UUID baselineEnvironmentId) {
        return new UpsertSchemaDriftConfigCommand(true, baseline, baselineEnvironmentId, 12);
    }

    @Test
    void createsAConfigurationAndAudits() {
        stubPipelineVisible();
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId)).thenReturn(Optional.empty());

        var view = service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.PROMOTION_SNAPSHOT, null));

        assertThat(view.enabled()).isTrue();
        assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(view.scanIntervalHours()).isEqualTo(12);
        verify(auditWriter).record(eq(AuditAction.SCHEMA_DRIFT_CONFIG_UPDATED),
                eq(AuditResourceType.SCHEMA_DRIFT_CONFIG), any(), eq(orgId), eq(actorId), any(),
                eq(null), eq(null));
    }

    @Test
    void updatesAnExistingConfigurationInPlace() {
        stubPipelineVisible();
        var existing = new SchemaDriftConfigEntity();
        existing.setId(UUID.randomUUID());
        existing.setOrganizationId(orgId);
        existing.setPipelineId(pipelineId);
        existing.setEnabled(false);
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(existing));

        var view = service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null));

        assertThat(view.id()).isEqualTo(existing.getId());
        assertThat(existing.isEnabled()).isTrue();
    }

    @Test
    void changingAwayFromBaselineEnvironmentClearsTheStaleDesignation() {
        stubPipelineVisible();
        var existing = new SchemaDriftConfigEntity();
        existing.setId(UUID.randomUUID());
        existing.setOrganizationId(orgId);
        existing.setPipelineId(pipelineId);
        existing.setBaseline(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        existing.setBaselineEnvironmentId(environmentId);
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(existing));

        // A replacement, not a patch — leaving it would silently point at an unused environment.
        var view = service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, environmentId));

        assertThat(view.baselineEnvironmentId()).isNull();
    }

    @Test
    void aValidBaselineEnvironmentIsAccepted() {
        stubPipelineVisible();
        stubEnvironment(environmentId, pipelineId, datasourceId);
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId)).thenReturn(Optional.empty());

        assertThat(service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId)).baselineEnvironmentId())
                .isEqualTo(environmentId);
    }

    @Test
    void baselineEnvironmentModeWithoutADesignationIsRefused() {
        stubPipelineVisible();

        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, null)))
                .isInstanceOf(SchemaDriftBaselineEnvironmentInvalidException.class);
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void aDesignatedEnvironmentOnAnotherPipelineIsRefused() {
        stubPipelineVisible();
        stubEnvironment(environmentId, UUID.randomUUID(), datasourceId);

        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId)))
                .isInstanceOf(SchemaDriftBaselineEnvironmentInvalidException.class);
    }

    @Test
    void anUnboundDesignatedEnvironmentIsRefused() {
        stubPipelineVisible();
        stubEnvironment(environmentId, pipelineId, null);

        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId)))
                .isInstanceOf(SchemaDriftBaselineEnvironmentInvalidException.class);
    }

    @Test
    void aMissingDesignatedEnvironmentIsRefused() {
        stubPipelineVisible();
        when(environmentLookupService.findById(environmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId)))
                .isInstanceOf(SchemaDriftBaselineEnvironmentInvalidException.class);
    }

    @Test
    void anUnconfiguredPipelineReadsAsDisabledDefaults() {
        stubPipelineVisible();
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId)).thenReturn(Optional.empty());

        assertThat(service.get(orgId, pipelineId)).satisfies(view -> {
            assertThat(view.id()).isNull();
            assertThat(view.enabled()).isFalse();
            assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
            assertThat(view.scanIntervalHours()).isEqualTo(24);
        });
    }

    @Test
    void aPipelineInAnotherOrganizationIsNotFound() {
        when(pipelineLookupService.findPipeline(pipelineId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(orgId, pipelineId))
                .isInstanceOf(SchemaChangePipelineNotFoundException.class);
        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null)))
                .isInstanceOf(SchemaChangePipelineNotFoundException.class);
    }

    @Test
    void listsOnlyConfiguredPipelines() {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setPipelineId(pipelineId);
        when(configRepository.findAllByOrganizationIdOrderByPipelineIdAsc(orgId))
                .thenReturn(List.of(config));

        assertThat(service.list(orgId)).singleElement()
                .satisfies(view -> assertThat(view.pipelineId()).isEqualTo(pipelineId));
    }

    @Test
    void aRacedFirstWriteForTheSamePipelineIsARetryableConflict() {
        stubPipelineVisible();
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId)).thenReturn(Optional.empty());
        when(configRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_schema_drift_configs_pipeline"));

        assertThatThrownBy(() -> service.upsert(orgId, actorId, pipelineId,
                command(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null)))
                .isInstanceOf(SchemaDriftConcurrentUpdateException.class);
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAuditRowUsesSnakeCaseKeys() {
        stubPipelineVisible();
        stubEnvironment(environmentId, pipelineId, datasourceId);
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId)).thenReturn(Optional.empty());

        service.upsert(orgId, actorId, pipelineId, command(SchemaDriftBaseline.BASELINE_ENVIRONMENT, environmentId));

        var metadata = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditWriter).record(any(), any(), any(), any(), any(), metadata.capture(), any(), any());
        assertThat(metadata.getValue())
                .containsEntry("pipeline_id", pipelineId.toString())
                .containsEntry("scan_interval_hours", 12)
                .containsEntry("baseline_environment_id", environmentId.toString());
    }
}
