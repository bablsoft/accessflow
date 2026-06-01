package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.MaskingPolicyView;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MaskingPolicyResponse(
        UUID id,
        UUID datasourceId,
        String columnRef,
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static MaskingPolicyResponse from(MaskingPolicyView view) {
        return new MaskingPolicyResponse(
                view.id(),
                view.datasourceId(),
                view.columnRef(),
                view.strategy(),
                view.strategyParams(),
                view.revealToRoles(),
                view.revealToGroupIds(),
                view.revealToUserIds(),
                view.enabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
