package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin-facing operations over detected behavioural anomalies (UBA, AF-383): paged listing and the
 * acknowledge / dismiss status transitions. Reads are also available to AUDITOR; mutations are
 * ADMIN-only (enforced at the controller).
 */
public interface BehaviorAnomalyAdminService {

    PageResponse<BehaviorAnomalyView> list(UUID organizationId, AnomalyListFilter filter,
                                           PageRequest pageRequest);

    /** @throws AnomalyNotFoundException when the anomaly is absent / cross-tenant. */
    BehaviorAnomalyView get(UUID organizationId, UUID anomalyId);

    /**
     * Transition OPEN -&gt; ACKNOWLEDGED, stamping the actor.
     *
     * @throws AnomalyNotFoundException when absent / cross-tenant.
     * @throws IllegalAnomalyStatusTransitionException when not currently OPEN.
     */
    BehaviorAnomalyView acknowledge(UUID organizationId, UUID anomalyId, UUID actorUserId);

    /**
     * Transition OPEN / ACKNOWLEDGED -&gt; DISMISSED, stamping the actor.
     *
     * @throws AnomalyNotFoundException when absent / cross-tenant.
     * @throws IllegalAnomalyStatusTransitionException when already DISMISSED.
     */
    BehaviorAnomalyView dismiss(UUID organizationId, UUID anomalyId, UUID actorUserId);
}
