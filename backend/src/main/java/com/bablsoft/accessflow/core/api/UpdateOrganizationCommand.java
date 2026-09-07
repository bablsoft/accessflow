package com.bablsoft.accessflow.core.api;

/**
 * Command to update an organization's name and quotas (AF-456). A {@code null} field leaves the
 * stored value unchanged (mirrors {@link UpdateUserCommand} null-means-skip semantics). To set a
 * quota to "unlimited", pass {@code 0}. The two {@code governs*} flags are the onboarding domain
 * hints (AF-898) — this is where an admin changes the answer given in the first-run wizard.
 */
public record UpdateOrganizationCommand(
        String name,
        Integer maxDatasources,
        Integer maxUsers,
        Integer maxQueriesPerDay,
        Boolean governsApis,
        Boolean governsDeployments
) {}
