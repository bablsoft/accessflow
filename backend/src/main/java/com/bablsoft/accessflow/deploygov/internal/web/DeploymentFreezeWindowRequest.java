package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowCommand;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Shared by POST (create) and PUT (full replacement) — the body is the complete definition. */
public record DeploymentFreezeWindowRequest(
        UUID pipelineId,
        UUID environmentId,
        Instant startsAt,
        Instant endsAt,
        List<Integer> daysOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 64, message = "{validation.deployment_freeze_window.timezone.size}")
        String timezone,
        @NotNull(message = "{validation.deployment_freeze_window.behavior.required}")
        FreezeBehavior behavior,
        String reason,
        Boolean enabled) {

    DeploymentFreezeWindowCommand toCommand(UUID organizationId) {
        return new DeploymentFreezeWindowCommand(organizationId, pipelineId, environmentId,
                startsAt, endsAt, daysOfWeek, startTime, endTime, timezone, behavior, reason,
                enabled);
    }
}
