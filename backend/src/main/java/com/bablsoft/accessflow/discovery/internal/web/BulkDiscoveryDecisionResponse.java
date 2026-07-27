package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.discovery.api.DiscoveryFindingService.BulkDecisionOutcome;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryRowStatus;

import java.util.List;
import java.util.UUID;

public record BulkDiscoveryDecisionResponse(List<Row> results) {

    public record Row(UUID findingId, DiscoveryRowStatus status,
                      DiscoveryFindingStatus newStatus) {
    }

    public static BulkDiscoveryDecisionResponse from(BulkDecisionOutcome outcome) {
        var rows = outcome.results().stream()
                .map(r -> new Row(r.findingId(), r.status(), r.newStatus()))
                .toList();
        return new BulkDiscoveryDecisionResponse(rows);
    }
}
