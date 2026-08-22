package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record DeploymentFreezeWindowResponse(
        UUID id,
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

    static DeploymentFreezeWindowResponse from(DeploymentFreezeWindowView view) {
        return new DeploymentFreezeWindowResponse(view.id(), view.pipelineId(),
                view.environmentId(), view.startsAt(), view.endsAt(), view.daysOfWeek(),
                view.startTime(), view.endTime(), view.timezone(), view.behavior(), view.reason(),
                view.enabled(), view.createdAt());
    }
}
