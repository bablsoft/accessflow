package com.bablsoft.accessflow.access.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One identity that can reach data without a permission row (#968) — never one row per grant.
 * {@code bypassKinds} is never empty: a user appears only when at least one path applies.
 * {@code queryAdmin} is non-null iff {@code bypassKinds} contains {@code QUERY_ADMIN};
 * {@code breakGlassGrants} is non-empty iff it contains {@code BREAK_GLASS}.
 */
public record PrivilegedAccessRow(
        UUID userId,
        String email,
        String displayName,
        UUID roleId,
        String roleName,
        boolean systemRole,
        Set<StandingBypassKind> bypassKinds,
        QueryAdminBypass queryAdmin,
        List<BreakGlassGrant> breakGlassGrants,
        PrivilegedAccessEvidence evidence
) {
    public PrivilegedAccessRow {
        bypassKinds = bypassKinds == null || bypassKinds.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(bypassKinds));
        breakGlassGrants = breakGlassGrants == null ? List.of() : List.copyOf(breakGlassGrants);
        evidence = evidence == null ? PrivilegedAccessEvidence.none() : evidence;
    }
}
