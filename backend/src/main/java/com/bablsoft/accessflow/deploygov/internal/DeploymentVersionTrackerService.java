package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;

/**
 * Maintains the {@code deployment_environment_versions} read model (#741). Deliberately
 * module-private — nothing outside deploygov may depend on the projection, and it never feeds
 * back into gate, approval or routing decisions.
 */
public interface DeploymentVersionTrackerService {

    /**
     * {@code APPROVED → EXECUTED}: shift current → previous and install the request as the
     * environment's current deploy. Upserts the row when the environment has none yet.
     */
    void recordExecution(DeploymentRequestEntity request);

    /**
     * An outcome report for the request. {@code SUCCEEDED} records {@code lastOutcome} only;
     * {@code FAILED}/{@code ROLLED_BACK} additionally reverts current to previous (single-level
     * undo). A report for a request that is not the environment's current deploy — or for an
     * environment with no row — is a no-op: the request row itself already records the outcome.
     */
    void recordOutcome(DeploymentRequestEntity request, DeploymentOutcome outcome);
}
