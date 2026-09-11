package com.bablsoft.accessflow.access.api;

import java.util.UUID;

/**
 * Filters for {@link PrivilegedAccessService#report}. Both optional; null means "no filter".
 * {@code kind} selects rows — a kept row still lists every kind that applies to it.
 */
public record PrivilegedAccessQuery(StandingBypassKind kind, UUID userId) {

    public static PrivilegedAccessQuery empty() {
        return new PrivilegedAccessQuery(null, null);
    }
}
