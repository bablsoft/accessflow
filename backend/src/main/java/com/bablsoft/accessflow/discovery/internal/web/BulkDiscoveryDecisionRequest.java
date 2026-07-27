package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.discovery.api.DiscoveryDecision;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BulkDiscoveryDecisionRequest(
        @NotEmpty(message = "{validation.discovery.finding_ids.required}")
        @Size(max = 100, message = "{validation.discovery.finding_ids.max}")
        List<UUID> findingIds,
        @NotNull(message = "{validation.discovery.decision.required}")
        DiscoveryDecision decision) {
}
