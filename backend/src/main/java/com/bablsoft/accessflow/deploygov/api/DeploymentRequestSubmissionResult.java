package com.bablsoft.accessflow.deploygov.api;

/**
 * Outcome of a deployment trigger, carrying the resulting request so the caller never has to
 * re-read it (a replay may be issued by a different user than the one who first triggered the run,
 * who would not necessarily pass the detail endpoint's visibility guard).
 *
 * <p>{@code replay} is {@code true} when an existing request was returned for a repeated trigger —
 * the web layer answers {@code 200} rather than {@code 202} and nothing was created.
 */
public record DeploymentRequestSubmissionResult(DeploymentRequestView request, boolean replay) {
}
