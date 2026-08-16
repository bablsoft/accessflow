package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Write side and read surfaces for out-of-office reviewer delegations (#622). The eligibility hot
 * path is {@link ReviewDelegationLookupService}; this interface backs the self-service profile
 * screen and the admin oversight list.
 */
public interface ReviewDelegationService {

    /**
     * Creates a delegation. Validates that both parties are active members of the organization,
     * that the delegate is not the delegator, that the window is non-empty, that a scoped
     * delegation names a resource of its declared kind, and that the delegator is within the
     * active-delegation cap.
     *
     * @throws IllegalReviewDelegationException when any of those does not hold
     */
    ReviewDelegationView create(CreateReviewDelegationCommand command);

    /**
     * Revokes a delegation, which is a soft state change: the row survives as the evidence that
     * decisions already recorded against it were validly authorised. Only the delegator may revoke.
     * Revoking an already-revoked delegation is a no-op, so a retry is safe.
     *
     * @return true when this call performed the revocation, false when it was already revoked —
     *         so the caller can skip auditing an event that did not happen
     *
     * @throws ReviewDelegationNotFoundException when the delegation is absent or in another org
     */
    boolean revoke(UUID delegationId, UUID organizationId, UUID actingUserId);

    /**
     * Active colleagues the caller may delegate to — everyone in the organization except
     * themselves, ordered by display name. See {@link ReviewDelegateCandidate} for why this exists
     * separately from the admin user listing.
     */
    List<ReviewDelegateCandidate> listDelegateCandidates(UUID organizationId, UUID callerId);

    /** Delegations this user granted to others, newest first, including revoked and expired ones. */
    List<ReviewDelegationView> listGrantedBy(UUID organizationId, UUID delegatorUserId);

    /** Delegations others granted to this user, newest first. */
    List<ReviewDelegationView> listReceivedBy(UUID organizationId, UUID delegateUserId);

    /** Org-wide oversight listing. */
    PageResponse<ReviewDelegationView> listForOrganization(UUID organizationId,
                                                          ReviewDelegationFilter filter,
                                                          PageRequest pageRequest);
}
