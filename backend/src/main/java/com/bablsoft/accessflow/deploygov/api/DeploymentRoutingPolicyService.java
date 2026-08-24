package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over the org's deployment routing policies (#691). Policies are returned in ascending
 * {@code priority} — the order the engine evaluates them in — and {@code priority} is unique per
 * organization so first-match is deterministic.
 */
public interface DeploymentRoutingPolicyService {

    List<DeploymentRoutingPolicyView> list(UUID organizationId);

    DeploymentRoutingPolicyView get(UUID id, UUID organizationId);

    DeploymentRoutingPolicyView create(CreateDeploymentRoutingPolicyCommand command);

    DeploymentRoutingPolicyView update(UUID id, UUID organizationId,
                                       UpdateDeploymentRoutingPolicyCommand command);

    void delete(UUID id, UUID organizationId);
}
