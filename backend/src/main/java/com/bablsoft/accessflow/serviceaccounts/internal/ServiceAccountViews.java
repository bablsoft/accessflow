package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;

import java.util.List;

/** Shared {@link ServiceAccountEntity} → {@link ServiceAccountView} mapper. */
final class ServiceAccountViews {

    private ServiceAccountViews() {
    }

    static ServiceAccountView toView(ServiceAccountEntity e) {
        return new ServiceAccountView(
                e.getUserId(),
                e.getOrganizationId(),
                e.getDescription(),
                e.getOwnerUserId(),
                e.getManagedBy(),
                // null stays null: it means "every tool", which an empty list does not.
                e.getMcpToolAllowList() == null ? null : List.of(e.getMcpToolAllowList()),
                e.getRateLimitPerMinute(),
                e.getRateLimitPerDay(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
