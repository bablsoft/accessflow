package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateApiMaskingPolicyRequest(
        @NotNull(message = "{validation.api_masking_policy.matcher.required}")
        ApiMaskingMatcherType matcherType,
        String operationId,
        @NotBlank(message = "{validation.api_masking_policy.field.required}")
        @Size(max = 2048, message = "{validation.api_masking_policy.field.required}")
        String fieldRef,
        @NotNull(message = "{validation.api_masking_policy.strategy.required}")
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        Boolean enabled) {

    public UpdateApiConnectorMaskingPolicyCommand toCommand() {
        return new UpdateApiConnectorMaskingPolicyCommand(matcherType, operationId, fieldRef, strategy,
                strategyParams, revealToRoles, revealToGroupIds, revealToUserIds, enabled);
    }
}
