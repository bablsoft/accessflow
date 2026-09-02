package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import java.time.Instant;

/**
 * Grouped drift projection (#742): one successfully deployed version on a pipeline and the last
 * instant it was executed anywhere. Produced by
 * {@link DeploymentRequestRepository#findSuccessfulVersionExecutions}.
 */
public record DeploymentVersionExecution(String version, Instant lastExecutedAt) {
}
