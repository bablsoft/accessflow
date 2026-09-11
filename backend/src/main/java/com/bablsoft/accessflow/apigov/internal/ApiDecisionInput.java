package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.UUID;

/**
 * Everything {@link ApiDecisionEvaluator} needs about one API call, real or hypothetical (issue
 * AF-967).
 *
 * <p>An explicit record rather than the request entity: the live path builds it from a persisted row
 * and the simulator builds it from a lookup view, and neither should have to fake the other's shape.
 * The connector fields are the governance-relevant ones only.
 */
record ApiDecisionInput(UUID organizationId, UUID connectorId, UUID reviewPlanId,
                        boolean requireReviewReads, boolean requireReviewWrites,
                        String verb, boolean write, String operationId,
                        AiOutcome aiOutcome, RiskLevel riskLevel) {
}
