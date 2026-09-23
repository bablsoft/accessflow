package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/**
 * Read and write one pipeline's schema drift configuration (#881, epic #870). Every method is
 * organization-scoped, 404-never-403: a pipeline in another organization reads as absent.
 */
public interface SchemaDriftConfigService {

    /** Every configured pipeline in the organization. Pipelines never configured are not listed. */
    List<SchemaDriftConfigView> list(UUID organizationId);

    /**
     * One pipeline's configuration, synthesizing the disabled defaults when no row exists.
     *
     * @throws SchemaChangePipelineNotFoundException when the pipeline is not in this organization
     */
    SchemaDriftConfigView get(UUID organizationId, UUID pipelineId);

    /**
     * Creates or replaces the configuration.
     *
     * @throws SchemaChangePipelineNotFoundException when the pipeline is not in this organization
     * @throws SchemaDriftBaselineEnvironmentInvalidException when the designated baseline environment
     *         is not a datasource-bound environment of this pipeline
     */
    SchemaDriftConfigView upsert(UUID organizationId, UUID actorId, UUID pipelineId,
                                 UpsertSchemaDriftConfigCommand command);
}
