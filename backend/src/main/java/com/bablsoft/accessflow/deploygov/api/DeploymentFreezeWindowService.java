package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin CRUD for deployment freeze windows (#688, epic #682). Definitions are validated against
 * the one-off ↔ recurring shape rules before persistence; evaluation lives in the module-internal
 * freeze-window evaluator.
 */
public interface DeploymentFreezeWindowService {

    PageResponse<DeploymentFreezeWindowView> list(UUID organizationId, PageRequest pageRequest);

    DeploymentFreezeWindowView get(UUID id, UUID organizationId);

    DeploymentFreezeWindowView create(DeploymentFreezeWindowCommand command);

    /** Full replacement — the command carries the complete new definition. */
    DeploymentFreezeWindowView update(UUID id, DeploymentFreezeWindowCommand command);

    void delete(UUID id, UUID organizationId);
}
