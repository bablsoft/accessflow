package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/**
 * The AI provider call or its response parsing failed. Fails safe: the state machine forces the
 * deployment to human review rather than letting routing auto-approve or auto-reject it.
 */
public record DeploymentAnalysisFailedEvent(UUID deploymentRequestId, String reason) {
}
