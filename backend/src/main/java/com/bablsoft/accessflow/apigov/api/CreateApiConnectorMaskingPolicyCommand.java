package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command to create a connector-level masking policy (AF-518). {@code operationId} is required when
 * {@code matcherType} is {@link ApiMaskingMatcherType#SCHEMA_FIELD} and ignored otherwise; the admin
 * service rejects a missing one with an {@link ApiRequestValidationException}.
 */
public record CreateApiConnectorMaskingPolicyCommand(
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
