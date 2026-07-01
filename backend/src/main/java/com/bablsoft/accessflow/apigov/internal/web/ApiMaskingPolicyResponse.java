package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyView;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ApiMaskingPolicyResponse(
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

    public static ApiMaskingPolicyResponse from(ApiConnectorMaskingPolicyView view) {
        return new ApiMaskingPolicyResponse(
                view.id(),
                view.connectorId(),
                view.matcherType(),
                view.operationId(),
                view.fieldRef(),
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
