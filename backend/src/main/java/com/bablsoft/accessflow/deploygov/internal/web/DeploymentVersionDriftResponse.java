package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView;

import java.time.Instant;

public record DeploymentVersionDriftResponse(
        String latestVersion,
        Instant latestDeployedAt,
        boolean drifted,
        Long daysBehind,
        Long deploymentsBehind) {

    static DeploymentVersionDriftResponse from(DeploymentVersionDriftView view) {
        return new DeploymentVersionDriftResponse(view.latestVersion(), view.latestDeployedAt(),
                view.drifted(), view.daysBehind(), view.deploymentsBehind());
    }
}
