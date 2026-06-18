package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating an organization from the platform UI (AF-456). Quota fields are optional
 * (null = unlimited); when supplied they must be non-negative (0 also means unlimited).
 */
public record CreateOrganizationRequest(
        @NotBlank(message = "{validation.organization.name.required}")
        @Size(min = 1, max = 255, message = "{validation.organization.name.size}") String name,
        @Size(max = 100, message = "{validation.organization.slug.size}") String slug,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxDatasources,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxUsers,
        @Min(value = 0, message = "{validation.organization.quota.min}") Integer maxQueriesPerDay
) {}
