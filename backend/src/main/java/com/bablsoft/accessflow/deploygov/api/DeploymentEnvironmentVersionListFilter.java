package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Filters for the org-wide version matrix (#742). {@code organizationId} is mandatory scoping;
 * everything else is optional — {@code tag} matches any element of the environment's tag array
 * exactly, {@code environment} is a case-insensitive environment-name match, and a null
 * {@code drifted} means "both". Filters never change what a row is compared against: the
 * per-pipeline latest is always computed over the pipeline's full row set.
 */
public record DeploymentEnvironmentVersionListFilter(
        UUID organizationId,
        UUID pipelineId,
        String tag,
        String environment,
        Boolean drifted) {
}
