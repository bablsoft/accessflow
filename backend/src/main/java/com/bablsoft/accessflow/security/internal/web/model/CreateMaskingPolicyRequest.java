package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateMaskingPolicyRequest(
        @NotBlank(message = "{validation.masking_column_ref.required}")
        @Size(max = 512, message = "{validation.masking_column_ref.size}")
        String columnRef,
        @NotNull(message = "{validation.masking_strategy.required}")
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        Boolean enabled
) {}
