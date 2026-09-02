package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeploymentEnvironmentVersionResponse(
        UUID pipelineId,
        String pipelineName,
        EnvironmentRef environment,
        String currentVersion,
        UUID currentRequestId,
        Instant deployedAt,
        String previousVersion,
        DeploymentOutcome lastOutcome,
        DeploymentVersionDriftResponse drift) {

    record EnvironmentRef(UUID id, String name, List<String> tags, int sortOrder) {
    }

    static DeploymentEnvironmentVersionResponse from(DeploymentEnvironmentVersionView view) {
        return new DeploymentEnvironmentVersionResponse(view.pipelineId(), view.pipelineName(),
                new EnvironmentRef(view.environmentId(), view.environmentName(), view.tags(),
                        view.sortOrder()),
                view.currentVersion(), view.currentRequestId(), view.deployedAt(),
                view.previousVersion(), view.lastOutcome(),
                DeploymentVersionDriftResponse.from(view.drift()));
    }
}
