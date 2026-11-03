package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Who may read an API request: its submitter, or a holder of {@code QUERY_VIEW_ALL} (REVIEWER and
 * ADMIN — parity with "View all query history"). Shared by detail, download and diff so the rule
 * cannot drift between them. Callers translate a miss into a 404, never a 403.
 */
final class ApiRequestVisibility {

    private ApiRequestVisibility() {
    }

    static boolean canViewAll(Set<Permission> callerPermissions) {
        return callerPermissions != null && callerPermissions.contains(Permission.QUERY_VIEW_ALL);
    }

    static boolean canView(ApiRequestEntity entity, UUID userId, Set<Permission> callerPermissions) {
        return canViewAll(callerPermissions) || entity.getSubmittedBy().equals(userId);
    }
}
