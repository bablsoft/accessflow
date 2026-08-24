package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/** A deployment request was persisted as {@code PENDING_AI}; triggers async AI risk analysis. */
public record DeploymentSubmittedEvent(UUID deploymentRequestId) {
}
