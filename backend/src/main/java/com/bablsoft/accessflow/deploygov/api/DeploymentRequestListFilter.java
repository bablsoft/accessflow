package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * AND-combined filter for {@link DeploymentRequestService#list}. Every field but
 * {@code organizationId} is optional. {@code environment} is matched on the environment name
 * (case-insensitive); {@code from} is inclusive and {@code to} exclusive.
 */
public record DeploymentRequestListFilter(
        UUID organizationId,
        UUID submittedByUserId,
        UUID pipelineId,
        String environment,
        String version,
        QueryStatus status,
        Instant from,
        Instant to) {
}
