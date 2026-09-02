package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the version matrix (#742): the version currently deployed to one environment, with
 * the single-level previous slot and the read-time drift block. A never-deployed environment (in
 * the per-pipeline matrix only) carries null version fields; a null {@code currentVersion} on a
 * deployed row means "unknown — see history" after consecutive rollbacks.
 */
public record DeploymentEnvironmentVersionView(
        UUID pipelineId,
        String pipelineName,
        UUID environmentId,
        String environmentName,
        List<String> tags,
        int sortOrder,
        String currentVersion,
        UUID currentRequestId,
        Instant deployedAt,
        String previousVersion,
        DeploymentOutcome lastOutcome,
        DeploymentVersionDriftView drift) {

    public DeploymentEnvironmentVersionView {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
