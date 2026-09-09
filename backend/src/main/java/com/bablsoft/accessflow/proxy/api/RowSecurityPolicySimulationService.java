package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.RowSecurityPolicyDraft;
import com.bablsoft.accessflow.core.api.SimulationWindow;

import java.util.UUID;

/**
 * Replays a datasource's executed queries against a draft row-security policy and reports what
 * would change — including the query shapes the predicate would make unrewritable (issue AF-630).
 * Strictly read-only and fully offline: no connection is opened to the customer database.
 */
public interface RowSecurityPolicySimulationService {

    /**
     * @throws com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException when the window is
     *         missing a bound, inverted, or longer than the configured maximum.
     */
    RowSecuritySimulationResult simulate(UUID organizationId, UUID datasourceId,
                                         SimulationWindow window, RowSecurityPolicyDraft draft);
}
