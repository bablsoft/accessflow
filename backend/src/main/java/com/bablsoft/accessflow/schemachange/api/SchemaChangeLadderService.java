package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/**
 * Read-only views the web UI (#883) needs to explain the promotion ladder without holding
 * {@code DEPLOYMENT_PIPELINE_MANAGE}: the organization's pipelines with their environments, and one
 * change set's ladder with a per-environment reason whenever a rung cannot be promoted. The ladder
 * is a preview of the promotion gate, never a substitute for it: it leaves out gate checks 7
 * ({@code can_ddl}, which depends on the promoter), 11 (review enforceability, which depends on
 * the datasource's plan) and 12 (the open-promotion conflict, which the rung's
 * {@code IN_PROGRESS} state already shows). See {@link SchemaChangeLadderBlocker} for the rest.
 */
public interface SchemaChangeLadderService {

    /** Every pipeline of the organization with its environments in ladder order. */
    List<SchemaChangePipelineView> listPipelines(UUID organizationId);

    /** The change set's ladder; {@link SchemaChangeSetNotFoundException} for a foreign or missing id. */
    SchemaChangeLadderView ladder(UUID organizationId, UUID changeSetId);
}
