package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Full definition of a freeze window, used by both create and update (update is a full
 * replacement — partial patch across the one-off ↔ recurring shape constraint is deliberately
 * not supported). Exactly one shape must be complete; {@code enabled} defaults to {@code true}
 * when null.
 */
public record DeploymentFreezeWindowCommand(
        UUID organizationId,
        UUID pipelineId,
        UUID environmentId,
        Instant startsAt,
        Instant endsAt,
        List<Integer> daysOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        String timezone,
        FreezeBehavior behavior,
        String reason,
        Boolean enabled) {
}
