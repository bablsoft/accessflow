package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's merged effective permission on a pipeline: the most-permissive union of their direct
 * grant and every unexpired grant of a group they belong to ({@code canTrigger} and
 * {@code canBreakGlass} OR-ed; {@code expiresAt} is {@code null} when any contributing grant
 * never expires, otherwise the latest contributing expiry).
 */
public record EffectiveDeploymentPermission(
        UUID pipelineId,
        UUID userId,
        boolean canTrigger,
        boolean canBreakGlass,
        Instant expiresAt) {
}
