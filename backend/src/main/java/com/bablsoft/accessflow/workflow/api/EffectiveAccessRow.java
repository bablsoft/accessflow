package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One user who could submit a statement class against a table, and every source that lets them
 * (issue AF-859).
 *
 * @param granted              computed over the <em>merged</em> effective permission, not per
 *                             source: booleans OR and allow-lists union, so two grants that each
 *                             fall short can together be enough
 * @param effectiveExpiresAt   the latest expiry among the contributing grants; {@code null} when any
 *                             of them is standing
 * @param canBreakGlass        reported alongside {@code granted}, never folded into it — "can write
 *                             anyway, as a logged emergency" is a different answer from "can write"
 */
public record EffectiveAccessRow(UUID userId, String email, String displayName, String roleName,
                                 boolean granted, TableScope tableScope, Instant effectiveExpiresAt,
                                 boolean canBreakGlass, List<AccessSource> sources) {

    public EffectiveAccessRow {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
