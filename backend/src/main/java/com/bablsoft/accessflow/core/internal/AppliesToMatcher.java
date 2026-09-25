package com.bablsoft.accessflow.core.internal;

import java.util.Set;
import java.util.UUID;

/**
 * The {@code applies_to_roles / _group_ids / _user_ids} scope shared by row-limit policies (#934)
 * and data budgets (#942): all three empty ⇒ every user; otherwise a match on any one list.
 */
final class AppliesToMatcher {

    private AppliesToMatcher() {
    }

    static boolean matches(String[] roles, UUID[] groups, UUID[] users, UUID userId,
                           String roleName, Set<UUID> groupIds) {
        boolean hasRoles = roles != null && roles.length > 0;
        boolean hasGroups = groups != null && groups.length > 0;
        boolean hasUsers = users != null && users.length > 0;
        if (!hasRoles && !hasGroups && !hasUsers) {
            return true;
        }
        if (hasRoles && roleName != null) {
            for (var allowed : roles) {
                if (allowed != null && roleName.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        if (hasUsers) {
            for (var allowed : users) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        if (hasGroups) {
            for (var allowed : groups) {
                if (groupIds.contains(allowed)) {
                    return true;
                }
            }
        }
        return false;
    }
}
