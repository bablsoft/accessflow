package com.bablsoft.accessflow.core.api;

/**
 * Command to update an organization's name and quotas (AF-456). A {@code null} field leaves the
 * stored value unchanged (mirrors {@link UpdateUserCommand} null-means-skip semantics). To set a
 * quota to "unlimited", pass {@code 0}.
 */
public record UpdateOrganizationCommand(
        String name,
        Integer maxDatasources,
        Integer maxUsers,
        Integer maxQueriesPerDay
) {}
