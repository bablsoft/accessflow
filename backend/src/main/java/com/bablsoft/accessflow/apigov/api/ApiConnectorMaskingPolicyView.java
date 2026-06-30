package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ApiConnectorMaskingPolicyView(
        UUID id,
        UUID connectorId,
        ApiMaskingMatcherType matcherType,
        String operationId,
        String fieldRef,
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public ApiConnectorMaskingPolicyView {
        strategyParams = strategyParams == null ? Map.of() : Map.copyOf(strategyParams);
        revealToRoles = revealToRoles == null ? List.of() : List.copyOf(revealToRoles);
        revealToGroupIds = revealToGroupIds == null ? List.of() : List.copyOf(revealToGroupIds);
        revealToUserIds = revealToUserIds == null ? List.of() : List.copyOf(revealToUserIds);
    }
}
