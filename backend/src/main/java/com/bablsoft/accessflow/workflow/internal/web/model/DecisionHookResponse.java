package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.DecisionHookView;

import java.time.Instant;
import java.util.UUID;

/** API response for a decision hook (#945). The secret is never returned, only whether it is set. */
public record DecisionHookResponse(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String name,
        String endpointUrl,
        int timeoutMs,
        boolean includeSql,
        boolean enabled,
        boolean secretConfigured,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static DecisionHookResponse from(DecisionHookView view) {
        return new DecisionHookResponse(view.id(), view.organizationId(), view.datasourceId(),
                view.name(), view.endpointUrl(), view.timeoutMs(), view.includeSql(), view.enabled(),
                view.secretConfigured(), view.version(), view.createdAt(), view.updatedAt());
    }
}
