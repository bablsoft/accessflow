package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves out-of-office reviewer delegations (#622) — "whose reviewer identity may this acting
 * user borrow right now?".
 *
 * <p>The window, revocation, and both parties' active flags are all applied here, against the
 * single injected {@link java.time.Clock} bean, so no caller can pass a stale instant or introduce
 * a second clock source. A delegation stops resolving the moment either party is deactivated,
 * without waiting for any cleanup job.
 *
 * <p><strong>Delegation is not transitive.</strong> Only direct delegators are returned: if A
 * delegates to B and B delegates to C, C gains nothing from A. This is enforced by construction —
 * the lookup never traverses — rather than by validation on write, which creating the two
 * delegations in the opposite order would defeat.
 *
 * <p><strong>This service never widens a permission set.</strong> Callers must gate on the acting
 * user's own {@link Permission} before consulting it; delegation decides <em>which requests</em> an
 * already-permitted reviewer may act on, never <em>whether</em> they may review at all.
 */
public interface ReviewDelegationLookupService {

    /**
     * Delegator identities {@code actingUserId} may borrow right now, ordered deterministically by
     * (created_at, id) so that a replayed decision records the same provenance.
     *
     * @param resourceKind when non-null together with {@code resourceId}, narrows the result to
     *                     delegations that are unrestricted or scoped to exactly that resource.
     *                     Pass {@code (null, null)} to get every active delegation and filter
     *                     per-row at the call site — the grouped-request case, where a bundle mixes
     *                     resources of both kinds.
     */
    List<DelegatedIdentity> findActiveForDelegate(UUID organizationId, UUID actingUserId,
                                                  DelegationScopeKind resourceKind, UUID resourceId);
}
