package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateApiConnectorMaskingPolicyCommand(
        ApiMaskingMatcherType matcherType,
        String operationId,
        String fieldRef,
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        Boolean enabled) {
}
