package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AccessTargetMatch;
import com.bablsoft.accessflow.core.api.AccessTargetMatchKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code applies_to_roles / _group_ids / _user_ids} scope shared by row-security policies,
 * row-limit policies (#934) and data budgets (#942): all three empty ⇒ every user; otherwise a
 * match on any one list. {@link #revealReasons} is the masking twin, where empty lists reveal to
 * nobody.
 */
final class AppliesToMatcher {

    private AppliesToMatcher() {
    }

    static boolean matches(String[] roles, UUID[] groups, UUID[] users, UUID userId,
                           String roleName, Set<UUID> groupIds) {
        return !explain(roles, groups, users, userId, roleName, groupIds).isEmpty();
    }

    /** Every reason the scope targets the user; empty when it does not (#946). */
    static List<AccessTargetMatch> explain(String[] roles, UUID[] groups, UUID[] users, UUID userId,
                                           String roleName, Set<UUID> groupIds) {
        boolean hasRoles = roles != null && roles.length > 0;
        boolean hasGroups = groups != null && groups.length > 0;
        boolean hasUsers = users != null && users.length > 0;
        if (!hasRoles && !hasGroups && !hasUsers) {
            return List.of(AccessTargetMatch.EVERYONE);
        }
        return listMatches(roles, groups, users, userId, roleName, groupIds);
    }

    /** Every reason a masking policy's reveal lists reveal to the user; empty = masked. */
    static List<AccessTargetMatch> revealReasons(String[] roles, UUID[] groups, UUID[] users,
                                                 UUID userId, String roleName, Set<UUID> groupIds) {
        return listMatches(roles, groups, users, userId, roleName, groupIds);
    }

    private static List<AccessTargetMatch> listMatches(String[] roles, UUID[] groups, UUID[] users,
                                                       UUID userId, String roleName,
                                                       Set<UUID> groupIds) {
        var out = new ArrayList<AccessTargetMatch>();
        if (roles != null && roleName != null) {
            for (var allowed : roles) {
                if (allowed != null && roleName.equalsIgnoreCase(allowed.trim())) {
                    out.add(new AccessTargetMatch(AccessTargetMatchKind.ROLE, roleName));
                    break;
                }
            }
        }
        if (users != null) {
            for (var allowed : users) {
                if (userId.equals(allowed)) {
                    out.add(new AccessTargetMatch(AccessTargetMatchKind.USER, userId.toString()));
                    break;
                }
            }
        }
        if (groups != null) {
            for (var allowed : groups) {
                if (allowed != null && groupIds.contains(allowed)) {
                    out.add(new AccessTargetMatch(AccessTargetMatchKind.GROUP, allowed.toString()));
                }
            }
        }
        return out;
    }
}
