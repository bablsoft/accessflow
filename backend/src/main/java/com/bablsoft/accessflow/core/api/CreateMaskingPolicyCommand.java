package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateMaskingPolicyCommand(
        String columnRef,
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        Boolean enabled) {
}
