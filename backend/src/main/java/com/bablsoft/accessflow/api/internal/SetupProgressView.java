package com.bablsoft.accessflow.api.internal;

/**
 * Snapshot of admin-onboarding progress for an organization. {@link #complete} is true once every
 * tracked step has been satisfied — the frontend setup-completion widget hides itself in that case.
 */
public record SetupProgressView(
        boolean datasourcesConfigured,
        boolean reviewPlansConfigured,
        boolean aiProviderConfigured,
        int completedSteps,
        int totalSteps,
        boolean complete) {
}
