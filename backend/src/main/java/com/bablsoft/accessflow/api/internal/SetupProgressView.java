package com.bablsoft.accessflow.api.internal;

/**
 * Snapshot of admin-onboarding progress for an organization. {@link #complete} is true once every
 * tracked step has been satisfied — the frontend setup-completion widget hides itself in that case.
 *
 * <p>The step set is not fixed: the three database-governance steps are always tracked, and the
 * API / deployment steps are added only for the governance domains the organization opted into
 * (AF-898). {@link #governsApis} and {@link #governsDeployments} are echoed back so the frontend
 * builds the same list rather than inferring it from the booleans.
 *
 * <p>{@link #apiConnectorsConfigured} and {@link #deploymentPipelinesConfigured} are <em>conditioned
 * on their domain flag</em>, not free-standing facts: each is false whenever its domain is
 * ungoverned, even for an organization that does own connectors or pipelines. An ungoverned domain
 * contributes no step, so its existence check is never run.
 */
public record SetupProgressView(
        boolean datasourcesConfigured,
        boolean reviewPlansConfigured,
        boolean aiProviderConfigured,
        boolean governsApis,
        boolean apiConnectorsConfigured,
        boolean governsDeployments,
        boolean deploymentPipelinesConfigured,
        int completedSteps,
        int totalSteps,
        boolean complete) {
}
