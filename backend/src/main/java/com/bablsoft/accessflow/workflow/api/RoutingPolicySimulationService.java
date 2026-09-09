package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.SimulationWindow;

import java.util.UUID;

/**
 * Replays a window of the organization's own historical query traffic against a draft routing
 * policy and reports what would change (issue AF-630). Strictly read-only: nothing is persisted and
 * nothing is executed against a customer database.
 */
public interface RoutingPolicySimulationService {

    /**
     * @throws com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException when the window is
     *         missing a bound, inverted, or longer than the configured maximum.
     */
    RoutingSimulationResult simulate(UUID organizationId, SimulationWindow window,
                                     UUID corpusDatasourceId, RoutingPolicyDraft draft);
}
