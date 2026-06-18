package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.OrganizationUsageView;

import java.util.UUID;

public record OrganizationUsageResponse(
        UUID organizationId,
        long datasourceCount,
        Integer maxDatasources,
        long userCount,
        Integer maxUsers,
        long queriesLast24h,
        Integer maxQueriesPerDay
) {
    public static OrganizationUsageResponse from(OrganizationUsageView view) {
        return new OrganizationUsageResponse(
                view.organizationId(),
                view.datasourceCount(),
                view.maxDatasources(),
                view.userCount(),
                view.maxUsers(),
                view.queriesLast24h(),
                view.maxQueriesPerDay());
    }
}
