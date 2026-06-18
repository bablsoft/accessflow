package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.OrganizationView;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String slug,
        boolean disabled,
        Integer maxDatasources,
        Integer maxUsers,
        Integer maxQueriesPerDay,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrganizationResponse from(OrganizationView view) {
        return new OrganizationResponse(
                view.id(),
                view.name(),
                view.slug(),
                view.disabled(),
                view.maxDatasources(),
                view.maxUsers(),
                view.maxQueriesPerDay(),
                view.createdAt(),
                view.updatedAt());
    }
}
