package com.bablsoft.accessflow.deploygov.internal.web;

import jakarta.validation.constraints.Size;

public record DeploymentDecisionRequest(
        @Size(max = 2000, message = "{validation.deployment_decision.comment.size}")
        String comment) {
}
