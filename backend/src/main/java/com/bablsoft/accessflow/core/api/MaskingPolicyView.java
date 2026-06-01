package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MaskingPolicyView(
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

    public MaskingPolicyView {
        strategyParams = strategyParams == null ? Map.of() : Map.copyOf(strategyParams);
        revealToRoles = revealToRoles == null ? List.of() : List.copyOf(revealToRoles);
        revealToGroupIds = revealToGroupIds == null ? List.of() : List.copyOf(revealToGroupIds);
        revealToUserIds = revealToUserIds == null ? List.of() : List.copyOf(revealToUserIds);
    }
}
