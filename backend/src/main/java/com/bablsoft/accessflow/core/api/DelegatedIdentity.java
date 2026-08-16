package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * A reviewer identity the acting user may currently borrow under an out-of-office delegation
 * (#622).
 *
 * <p>{@code delegatorRoleName} is the delegator's <em>current</em> effective role name, resolved at
 * lookup time and never snapshotted onto the delegation row — a delegation is a pointer to an
 * identity, not a frozen copy of its powers, so a role change or role removal mid-window takes
 * effect immediately.
 *
 * <p>A null {@code scopeKind} (necessarily paired with a null {@code scopeId}) means the delegation
 * is unrestricted and applies to every review queue.
 */
public record DelegatedIdentity(UUID delegationId,
                                UUID delegatorUserId,
                                String delegatorRoleName,
                                DelegationScopeKind scopeKind,
                                UUID scopeId) {

    /** Whether this delegation applies to every review queue rather than one named resource. */
    public boolean isUnrestricted() {
        return scopeKind == null;
    }

    /**
     * Whether this delegation covers the given resource — true when unrestricted, or when it is
     * scoped to exactly that resource. A null {@code resourceId} never matches a scoped delegation,
     * so a request with no resource of this kind fails closed.
     */
    public boolean covers(DelegationScopeKind kind, UUID resourceId) {
        if (isUnrestricted()) {
            return true;
        }
        return scopeKind == kind && resourceId != null && scopeId.equals(resourceId);
    }
}
