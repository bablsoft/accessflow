package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Freeze-window evaluation for other modules (#877, epic #870) — a schema promotion into a frozen
 * environment must be refused with exactly the semantics deployments get. Delegates to the
 * module-private evaluator and preserves its contract:
 *
 * <ul>
 *   <li><b>Matching.</b> The organization's enabled windows whose scope is org-wide, the pipeline,
 *       or the environment.</li>
 *   <li><b>Precedence.</b> Among simultaneously active windows the most specific scope wins
 *       (environment &gt; pipeline &gt; org-wide); within a tier {@code REJECT} beats
 *       {@code HOLD}, then the oldest window.</li>
 *   <li><b>Fail closed.</b> A window whose stored definition cannot be evaluated counts as an
 *       active {@code HOLD} — never {@code REJECT} — so a broken row can hold but never destroy.</li>
 * </ul>
 */
public interface DeploymentFreezeLookupService {

    /** The freeze in effect now for {@code (organization, pipeline, environment)}, if any. */
    Optional<ActiveDeploymentFreezeView> evaluate(UUID organizationId, UUID pipelineId, UUID environmentId);

    /** The freeze that would be in effect at {@code at} — the scheduled / simulated path. */
    Optional<ActiveDeploymentFreezeView> evaluate(UUID organizationId, UUID pipelineId, UUID environmentId,
                                                  Instant at);
}
