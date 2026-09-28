package com.bablsoft.accessflow.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for external decision hooks (#945): at most one organization default and one hook per
 * datasource. Every operation is scoped to {@code organizationId}; a hook of another organization
 * reads as missing.
 */
public interface DecisionHookService {

    /** The organization default first, then by name. */
    List<DecisionHookView> list(UUID organizationId);

    DecisionHookView get(UUID organizationId, UUID id);

    DecisionHookView create(CreateDecisionHookCommand command);

    DecisionHookView update(UUID organizationId, UUID id, UpdateDecisionHookCommand command);

    void delete(UUID organizationId, UUID id);

    /**
     * Sends one signed synthetic request and reports what the live path would have concluded. It
     * bypasses and does not feed the circuit breaker, and works on a disabled hook.
     */
    DecisionHookTestResult test(UUID organizationId, UUID id);
}
