package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.deploygov.events.DeploymentBreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens the mandatory retro-review for a break-glass deployment (#692). A plain synchronous
 * {@link EventListener} — not {@code @ApplicationModuleListener} — so the {@code break_glass_events}
 * row commits atomically with the force-approved deployment request inside deploygov's submitting
 * transaction; a break-glass deployment must never exist without its retro-review. Event-based
 * (AF-567 shape) so deploygov does not depend on workflow, keeping the module graph acyclic.
 */
@Component
@RequiredArgsConstructor
class DeploymentBreakGlassReviewListener {

    private final BreakGlassService breakGlassService;

    @EventListener
    void onDeploymentBreakGlassExecuted(DeploymentBreakGlassExecutedEvent event) {
        breakGlassService.openDeploymentBreakGlassReview(
                new BreakGlassService.DeploymentBreakGlassReview(
                        event.organizationId(), event.deploymentRequestId(), event.pipelineId(),
                        event.submitterUserId(), event.justification()));
    }
}
