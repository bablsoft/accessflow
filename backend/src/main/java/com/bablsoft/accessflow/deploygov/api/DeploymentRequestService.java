package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Submission, listing, and cancellation of governed deployment requests (#691). A trigger flows
 * through AI risk scoring → routing → human review before the deployment gate (#693) can open.
 */
public interface DeploymentRequestService {

    /**
     * Trigger a deployment. Idempotent on
     * {@code (pipelineId, environment, version, externalRunId)} when an {@code externalRunId} is
     * supplied: a repeated trigger returns the existing request with
     * {@link DeploymentRequestSubmissionResult#replay()} set and creates nothing. The trigger
     * permission is checked before the replay lookup, so a caller without a grant cannot use a
     * repeat to probe for existing requests.
     *
     * @throws DeploymentPipelineNotFoundException     unknown or cross-org pipeline
     * @throws DeploymentEnvironmentNotFoundException  the pipeline has no environment with that name
     * @throws DeploymentRequestPermissionException    the caller holds no {@code can_trigger} grant
     */
    DeploymentRequestSubmissionResult submit(SubmitDeploymentRequestCommand command);

    /**
     * True when these permissions may see every deployment request in the organization rather than
     * only their own submissions. The web layer uses it to scope the list filter, so listing and
     * {@link #get} agree on who can see what.
     */
    boolean canViewAll(Set<Permission> callerPermissions);

    /**
     * Lists deployment requests matching {@code filter}. Callers for whom
     * {@link #canViewAll(Set)} holds pass a {@code null} {@code submittedByUserId} to see the whole
     * organization; everyone else sets it to their own id.
     */
    PageResponse<DeploymentRequestView> list(DeploymentRequestListFilter filter, PageRequest pageRequest);

    /**
     * Returns the detail view of one request. Visible to the submitter and — per the
     * {@code docs/07-security.md} role matrix — to any holder of {@code DEPLOYMENT_REVIEW} or
     * {@code QUERY_ADMIN} in the organization. Everyone else gets
     * {@link DeploymentRequestNotFoundException}, never a 403, so the endpoint is not an
     * existence oracle.
     */
    DeploymentRequestView get(UUID id, UUID organizationId, UUID userId,
                              Set<Permission> callerPermissions);

    /**
     * Submitter cancels a request awaiting review, or an approved one whose deferred
     * ({@code scheduledFor}) run has not yet fired.
     *
     * @throws DeploymentRequestPermissionException   the caller is not the submitter
     * @throws IllegalDeploymentRequestStateException the request is in no cancellable state
     */
    void cancel(UUID id, UUID organizationId, UUID userId);
}
