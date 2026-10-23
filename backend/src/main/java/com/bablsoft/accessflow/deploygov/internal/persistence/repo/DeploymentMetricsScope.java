package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scope of one metrics read (#984): org-scoped always; {@code pipelineId} and
 * {@code environmentIds} are optional narrowing ({@code null} = no filter; a non-null
 * {@code environmentIds} must be non-empty). Window is {@code [from, to)}.
 */
public record DeploymentMetricsScope(
        UUID organizationId,
        UUID pipelineId,
        List<UUID> environmentIds,
        Instant from,
        Instant to) {
}
