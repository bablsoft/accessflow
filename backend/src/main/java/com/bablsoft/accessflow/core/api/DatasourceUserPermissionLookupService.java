package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionLookupService {

    /**
     * The user's <b>effective</b> permission on a datasource — the most-permissive union of their
     * direct grant (if any) and every unexpired group grant for a group they belong to (AF-530).
     * Boolean flags are OR-ed; allow-lists (allowed schemas/tables) merge to their union (any
     * contributor with no restriction ⇒ all allowed); the restricted-columns mask merges to the
     * intersection (a column is masked only when every contributor masks it). Expired grants
     * contribute nothing; returns empty when no unexpired grant applies.
     */
    Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId);

    /**
     * The user's <b>direct</b> per-user grant on a datasource, ignoring group grants and expiry.
     * Used by JIT-access materialisation, which manages the per-user
     * {@code datasource_user_permissions} row specifically and must not act on a group grant.
     */
    Optional<DatasourceUserPermissionView> findDirectFor(UUID userId, UUID datasourceId);

    /**
     * The user's currently-effective break-glass grants — permissions with {@code can_break_glass}
     * set whose {@code expires_at} is null or in the future. Backs the break-glass eligibility check
     * (AF-385).
     */
    List<DatasourceUserPermissionView> findBreakGlassEligible(UUID userId);
}
