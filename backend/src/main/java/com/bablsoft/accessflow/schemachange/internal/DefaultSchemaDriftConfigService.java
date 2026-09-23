package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaselineEnvironmentInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConcurrentUpdateException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigService;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigView;
import com.bablsoft.accessflow.schemachange.api.UpsertSchemaDriftConfigCommand;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Per-pipeline drift configuration: the opt-in the scheduled job drains (#881). */
@Service
@RequiredArgsConstructor
public class DefaultSchemaDriftConfigService implements SchemaDriftConfigService {

    private final SchemaDriftConfigRepository configRepository;
    private final DeploymentPipelineLookupService pipelineLookupService;
    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final SchemaChangeAuditWriter auditWriter;

    @Override
    @Transactional(readOnly = true)
    public List<SchemaDriftConfigView> list(UUID organizationId) {
        return configRepository.findAllByOrganizationIdOrderByPipelineIdAsc(organizationId).stream()
                .map(SchemaDriftViewMapper::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SchemaDriftConfigView get(UUID organizationId, UUID pipelineId) {
        requirePipeline(organizationId, pipelineId);
        return configRepository.findByPipelineIdAndOrganizationId(pipelineId, organizationId)
                .map(SchemaDriftViewMapper::toView)
                .orElseGet(() -> SchemaDriftViewMapper.disabledDefaults(organizationId, pipelineId));
    }

    @Override
    @Transactional
    public SchemaDriftConfigView upsert(UUID organizationId, UUID actorId, UUID pipelineId,
                                        UpsertSchemaDriftConfigCommand command) {
        requirePipeline(organizationId, pipelineId);
        validateBaseline(pipelineId, command);

        var config = configRepository.findByPipelineIdAndOrganizationId(pipelineId, organizationId)
                .orElseGet(() -> {
                    var created = new SchemaDriftConfigEntity();
                    created.setId(UUID.randomUUID());
                    created.setOrganizationId(organizationId);
                    created.setPipelineId(pipelineId);
                    return created;
                });
        config.setEnabled(command.enabled());
        config.setBaseline(command.baseline());
        // Total, not a patch: dropping the designation must actually drop it, or a mode change would
        // leave a stale environment silently in place.
        config.setBaselineEnvironmentId(command.baseline() == SchemaDriftBaseline.BASELINE_ENVIRONMENT
                ? command.baselineEnvironmentId() : null);
        config.setScanIntervalHours(command.scanIntervalHours());
        SchemaDriftConfigEntity saved;
        try {
            // Flushed here so a raced first write (the pipeline's unique key) or a raced update
            // (the version) is a retryable 409 rather than a 500 at commit.
            saved = configRepository.saveAndFlush(config);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
            throw new SchemaDriftConcurrentUpdateException(pipelineId);
        }

        recordAudit(organizationId, actorId, saved);
        return SchemaDriftViewMapper.toView(saved);
    }

    private void requirePipeline(UUID organizationId, UUID pipelineId) {
        pipelineLookupService.findPipeline(pipelineId, organizationId)
                .orElseThrow(() -> new SchemaChangePipelineNotFoundException(pipelineId));
    }

    /**
     * Refused up front so an operator finds out now, rather than discovering hours later that every
     * scan recorded a reason instead of findings. The scan path re-checks anyway — an environment can
     * be rebound or deleted after this was saved.
     */
    private void validateBaseline(UUID pipelineId, UpsertSchemaDriftConfigCommand command) {
        if (command.baseline() != SchemaDriftBaseline.BASELINE_ENVIRONMENT) {
            return;
        }
        var baselineEnvironmentId = command.baselineEnvironmentId();
        if (baselineEnvironmentId == null) {
            throw new SchemaDriftBaselineEnvironmentInvalidException(pipelineId, null);
        }
        var environment = environmentLookupService.findById(baselineEnvironmentId).orElse(null);
        if (environment == null || !pipelineId.equals(environment.pipelineId())
                || environment.datasourceId() == null) {
            throw new SchemaDriftBaselineEnvironmentInvalidException(pipelineId, baselineEnvironmentId);
        }
    }

    private void recordAudit(UUID organizationId, UUID actorId, SchemaDriftConfigEntity config) {
        var metadata = new HashMap<String, Object>();
        metadata.put("pipeline_id", config.getPipelineId().toString());
        metadata.put("enabled", config.isEnabled());
        metadata.put("baseline", config.getBaseline().name());
        metadata.put("scan_interval_hours", config.getScanIntervalHours());
        if (config.getBaselineEnvironmentId() != null) {
            metadata.put("baseline_environment_id", config.getBaselineEnvironmentId().toString());
        }
        auditWriter.record(AuditAction.SCHEMA_DRIFT_CONFIG_UPDATED, AuditResourceType.SCHEMA_DRIFT_CONFIG,
                config.getId(), organizationId, actorId, metadata, null, null);
    }
}
