package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * The deployment gate (#693): the fail-closed releasability answer CI pipelines poll, and the
 * execution confirmation that moves an {@code APPROVED} request to {@code EXECUTED} once the
 * pipeline proceeded.
 *
 * <p>Releasability is computed by a single pure function —
 * {@code status == APPROVED && !frozen && (scheduledFor == null || scheduledFor <= now)} — where
 * {@code frozen} is any active freeze window ({@code HOLD} or {@code REJECT}) for the request's
 * pipeline/environment; break-glass requests skip the freeze check. Any lookup or evaluation
 * error answers not-releasable: no code path answers "releasable" by default.
 */
public interface DeploymentGateService {

    /**
     * Resolve the newest request for {@code (pipeline, version, environment)} — names
     * case-insensitive within the caller's organization — and answer its releasability.
     *
     * @throws DeploymentPipelineNotFoundException    unknown pipeline name in the caller's org
     * @throws DeploymentEnvironmentNotFoundException the pipeline has no environment with that name
     * @throws DeploymentRequestNotFoundException     no request exists for the tuple, or the
     *                                                request is not visible to the caller
     */
    DeploymentGateView gate(String pipelineName, String environmentName, String version,
                            UUID organizationId, UUID callerId, Set<Permission> callerPermissions);

    /**
     * Answer releasability for one request by id.
     *
     * @throws DeploymentRequestNotFoundException unknown or cross-org id, or not visible
     */
    DeploymentGateView gateByRequestId(UUID requestId, UUID organizationId, UUID callerId,
                                       Set<Permission> callerPermissions);

    /**
     * The pipeline acknowledges it proceeded: {@code APPROVED → EXECUTED}. Idempotent — confirming
     * an already-{@code EXECUTED} request returns it unchanged.
     *
     * @throws DeploymentRequestNotFoundException     unknown or cross-org id
     * @throws DeploymentRequestPermissionException   the caller is neither the submitter, a
     *                                                {@code can_trigger} holder, nor an admin
     * @throws IllegalDeploymentRequestStateException the request is not {@code APPROVED}
     * @throws DeploymentNotReleasableException       approved but not currently releasable
     */
    DeploymentRequestView confirmExecution(UUID requestId, UUID organizationId, UUID callerId,
                                           Set<Permission> callerPermissions, String ipAddress);
}
