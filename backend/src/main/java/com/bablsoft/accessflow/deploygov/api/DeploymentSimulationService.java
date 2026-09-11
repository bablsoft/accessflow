package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Traces one hypothetical deployment through the evaluators that decide real ones (issue AF-967) —
 * the pipeline gates, the effective trigger grant, the freeze window, every routing policy in priority
 * order, the environment's own review policy, who could review it, the deferred-release moment, and
 * the release gate itself.
 *
 * <p>The sharpest of the three explainers, because a deployment is blocked by the <em>gate</em> rather
 * than by the decision, and the freeze-window / plan-approver / break-glass interaction is not visible
 * on any single screen. {@code GATE_RELEASABILITY} calls the gate's own pure function rather than a
 * copy of it, so the answer cannot drift from what the CI job will see.
 *
 * <p><strong>Read-only.</strong> A simulation creates no {@code deployment_requests} row, publishes no
 * event, writes no audit row of its own, sends no notification and makes no AI call. That is enforced
 * structurally rather than by convention: the implementation is wired only to lookups and pure
 * evaluators, and its test asserts as much against its declared field types.
 */
public interface DeploymentSimulationService {

    DeploymentSimulationResult simulate(UUID organizationId, DeploymentSimulationInput input);
}
