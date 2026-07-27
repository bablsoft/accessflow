package com.bablsoft.accessflow.discovery.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Worklist over proposed sensitive-column classifications (AF-623). Confirming a finding applies
 * the classification tag through the existing AF-447 service (which auto-derives masking);
 * dismissing permanently suppresses the proposal for future scans.
 */
public interface DiscoveryFindingService {

    /**
     * Pages the datasource's findings, newest detection first, optionally filtered by status
     * ({@code null} = all).
     */
    PageResponse<DiscoveryFindingView> find(UUID datasourceId, UUID organizationId,
                                            DiscoveryFindingStatus status, PageRequest page);

    /**
     * Applies the decision to each finding independently (partial success — one bad row never
     * rolls back the others) and reports a per-finding outcome row.
     */
    BulkDecisionOutcome decide(UUID datasourceId, UUID organizationId, UUID actorId,
                               List<UUID> findingIds, DiscoveryDecision decision);

    /** Per-finding outcomes of a bulk decision, in the order the ids were submitted. */
    record BulkDecisionOutcome(List<Row> results) {

        public BulkDecisionOutcome {
            results = results == null ? List.of() : List.copyOf(results);
        }

        /**
         * {@code newStatus} is the finding's status after the decision and {@code finding} its
         * post-decision view (both {@code null} on NOT_FOUND).
         */
        public record Row(UUID findingId, DiscoveryRowStatus status,
                          DiscoveryFindingStatus newStatus, DiscoveryFindingView finding) {
        }
    }
}
