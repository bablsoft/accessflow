package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.apigov.events.ApiBreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens the mandatory retro-review for a break-glass API execution (AF-500). A plain synchronous
 * {@link EventListener} — not {@code @ApplicationModuleListener} — so the {@code break_glass_events}
 * row commits atomically with the executed API request inside apigov's submitting transaction; a
 * break-glass execution must never exist without its retro-review. Event-based (AF-567) so apigov
 * does not depend on workflow, which would close an access → apigov → workflow → access module
 * cycle.
 */
@Component
@RequiredArgsConstructor
class ApiBreakGlassReviewListener {

    private final BreakGlassService breakGlassService;

    @EventListener
    void onApiBreakGlassExecuted(ApiBreakGlassExecutedEvent event) {
        breakGlassService.openApiBreakGlassReview(new BreakGlassService.ApiBreakGlassReview(
                event.organizationId(), event.apiRequestId(), event.connectorId(),
                event.submitterUserId(), event.justification()));
    }
}
