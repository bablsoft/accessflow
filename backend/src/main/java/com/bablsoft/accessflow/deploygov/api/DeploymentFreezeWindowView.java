package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a deployment freeze window. Exactly one shape is populated: one-off
 * ({@code startsAt}/{@code endsAt}) or recurring weekly ({@code daysOfWeek} in ISO-8601
 * numbering, 1 = Monday … 7 = Sunday, plus wall-clock {@code startTime}/{@code endTime} in
 * {@code timezone}).
 */
public record DeploymentFreezeWindowView(
        UUID id,
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
        boolean enabled,
        Instant createdAt) {
}
