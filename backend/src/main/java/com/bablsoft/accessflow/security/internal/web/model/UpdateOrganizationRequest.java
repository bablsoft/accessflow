package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an organization's name and quotas (AF-456). All fields are optional; a
 * null field leaves the stored value unchanged. Pass {@code 0} for a quota to set it to unlimited.
 */
public record UpdateOrganizationRequest(
        @Size(min = 1, max = 255, message = "{validation.organization.name.size}") String name,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxDatasources,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxUsers,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxQueriesPerDay
) {}
