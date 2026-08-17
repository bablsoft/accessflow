package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * One identity under which an acting user may be evaluated for reviewer eligibility (#622) —
 * either their own, or one borrowed from a delegator.
 *
 * <p><strong>Eligibility must be evaluated per candidate as a whole</strong>, never by OR-ing the
 * individual predicates across candidates. A review flow typically checks several things (does an
 * approver rule match at this stage? is this user in the datasource's reviewer scope?); satisfying
 * one from delegator A and another from delegator B would synthesize an identity nobody actually
 * holds and let a delegate act where neither delegator could.
 *
 * <p>{@code onBehalfOfUserId} and {@code delegationId} are null for the acting user's own identity
 * and set for a borrowed one; they are persisted on the resulting decision row so the audit trail
 * names both parties and pins the exact grant that authorised it.
 */
public record ReviewCandidate(UUID userId,
                              String roleName,
                              UUID onBehalfOfUserId,
                              UUID delegationId) {

    /** The acting user's own identity. Always evaluated first. */
    public static ReviewCandidate self(UUID userId, String roleName) {
        return new ReviewCandidate(userId, roleName, null, null);
    }

    /** An identity borrowed from a delegator under an active delegation. */
    public static ReviewCandidate borrowed(DelegatedIdentity identity) {
        return new ReviewCandidate(identity.delegatorUserId(), identity.delegatorRoleName(),
                identity.delegatorUserId(), identity.delegationId());
    }

    public boolean isDelegated() {
        return onBehalfOfUserId != null;
    }
}
