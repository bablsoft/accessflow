package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNoDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves everything one on-demand drift scan needs, and answers its 404s, before anything is
 * written (#881).
 *
 * <p>Ordering is deliberate: an environment the caller may not see must be indistinguishable from a
 * busy one, so every not-found check runs before the in-flight check the caller would otherwise be
 * able to probe with. The environment is resolved through its pipeline, which is what scopes it to
 * the organization — {@code DeploymentEnvironmentLookupService} is not organization-scoped by
 * design.
 */
@Component
@RequiredArgsConstructor
class SchemaDriftScanContextResolver {

    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DeploymentPipelineLookupService pipelineLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final SchemaDriftConfigRepository configRepository;

    SchemaDriftScanContext resolve(UUID organizationId, UUID environmentId) {
        var environment = environmentLookupService.findById(environmentId)
                .orElseThrow(() -> new SchemaChangeEnvironmentNotFoundException(environmentId));
        // A pipeline in another organization reads as absent, so its environments do too.
        pipelineLookupService.findPipeline(environment.pipelineId(), organizationId)
                .orElseThrow(() -> new SchemaChangeEnvironmentNotFoundException(environmentId));
        if (environment.datasourceId() == null) {
            throw new SchemaChangeEnvironmentNoDatasourceException(environmentId);
        }
        var datasource = datasourceAdminService.getForAdmin(environment.datasourceId(), organizationId);
        var config = configRepository.findByPipelineIdAndOrganizationId(environment.pipelineId(), organizationId)
                .orElse(null);
        // A manual scan works on an unconfigured pipeline: the configuration decides what the
        // scheduler does, not whether drift can be measured at all.
        var baseline = config == null
                ? SchemaDriftBaseline.PREVIOUS_ENVIRONMENT
                : config.getBaseline();
        var baselineEnvironmentId = config == null ? null : config.getBaselineEnvironmentId();
        return new SchemaDriftScanContext(organizationId, environment.pipelineId(), environmentId,
                datasource.id(), datasource.dbType(), baseline, baselineEnvironmentId);
    }
}
