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
     * The unexpired grants that <em>feed</em> {@link #findFor}, before they are merged (AF-859).
     *
     * <p>{@code findFor} merges over exactly this list, so a caller that needs to name the
     * contributing grants gets the real inputs rather than a second derivation that could disagree
     * with the one enforcement uses. Empty when no unexpired grant applies.
     */
    List<DatasourcePermissionContribution> findContributions(UUID userId, UUID datasourceId);

    /**
     * Every unexpired contribution on a datasource, for every user who has one (AF-859) — the
     * direct rows plus each group row expanded across that group's members.
     *
     * <p>Backs the reverse index ("who can reach this table"). Deliberately not paginated: the
     * merge that decides whether a user is actually granted runs in Java over a user's whole set of
     * contributions, so a caller must see all of them before it can filter or slice.
     */
    List<DatasourcePermissionContribution> findContributionsForDatasource(UUID datasourceId);

    /**
     * The user's currently-effective break-glass grants — permissions with {@code can_break_glass}
     * set whose {@code expires_at} is null or in the future. Backs the break-glass eligibility check
     * (AF-385).
     */
    List<DatasourceUserPermissionView> findBreakGlassEligible(UUID userId);
}
