package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.MaskingPolicyDraft;
import com.bablsoft.accessflow.core.api.SimulationWindow;

import java.util.UUID;

/**
 * Replays a datasource's persisted result sets against a draft masking policy and reports which
 * columns each submitter would newly see masked, or newly see in the clear (issue AF-630).
 * Strictly read-only: no cell values are read, and nothing runs against the customer database.
 */
public interface MaskingPolicySimulationService {

    /**
     * @throws com.bablsoft.accessflow.core.api.InvalidSimulationPeriodException when the window is
     *         missing a bound, inverted, or longer than the configured maximum.
     */
    MaskingSimulationResult simulate(UUID organizationId, UUID datasourceId,
                                     SimulationWindow window, MaskingPolicyDraft draft);
}
