package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Traces one hypothetical request through the evaluators that decide real ones (issue AF-859), so
 * an admin can answer "why would this be rejected?" without submitting a probe query in production.
 *
 * <p>Strictly read-only, and structurally so: an implementation must reach no persistence service,
 * no event publisher, no AI analyzer, no notification dispatcher and no customer database. Nothing
 * is written, nothing is executed, and no {@code query_requests} row is created.
 *
 * <p>Distinct from {@link RoutingPolicySimulationService}, which replays <em>historical traffic</em>
 * against a <em>draft policy</em> and reports an aggregated diff. This replays <em>current policy</em>
 * against <em>one hypothetical request</em>.
 */
public interface AccessSimulationService {

    /**
     * @throws com.bablsoft.accessflow.core.api.DatasourceNotFoundException when the datasource is
     *         missing or belongs to another organization
     * @throws com.bablsoft.accessflow.core.api.UserNotFoundException when the simulated user is
     *         missing or belongs to another organization
     */
    AccessSimulationResult simulate(UUID organizationId, AccessSimulationInput input);
}
