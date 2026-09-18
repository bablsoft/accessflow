package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.List;
import java.util.UUID;

/**
 * Lifecycle of the human → service-account delegations that authorise the
 * {@code X-AccessFlow-On-Behalf-Of} header (#874). A grant records that the human consents to
 * being named; it never widens the service account's permissions. Two callers: a human granting for
 * themselves ({@code /me/service-account-delegations}) and an admin granting on a human's behalf
 * ({@code /admin/service-accounts/{id}/delegated-principals}) — both land here.
 */
public interface ServiceAccountDelegationService {

    /**
     * @throws ServiceAccountNotFoundException               the service account is unknown, in
     *                                                       another organization, or a human
     * @throws ServiceAccountDelegationPrincipalInvalidException the principal is unknown, in another
     *                                                       organization, inactive, or not a HUMAN
     * @throws ServiceAccountDelegationExistsException       a live (unrevoked, unexpired) grant already
     *                                                       exists — an expired one is quietly revoked
     *                                                       and replaced
     * @throws ServiceAccountDelegationInvalidException      {@code expiresAt} is not in the future
     */
    ServiceAccountDelegationView grant(UUID organizationId, UUID actorUserId,
                                       GrantServiceAccountDelegationCommand command);

    /** Every grant of the account, newest first, revoked and expired included. */
    List<ServiceAccountDelegationView> listForServiceAccount(UUID organizationId, UUID serviceAccountUserId);

    /** Every grant naming the human, newest first. */
    List<ServiceAccountDelegationView> listForPrincipal(UUID organizationId, UUID principalUserId);

    /**
     * Soft-revokes and returns the (now revoked) grant. Idempotent. Each non-null scope must match:
     * the admin surface binds the grant to the service account in its path, the self-service
     * surface to the calling human — so neither can revoke (or audit) somebody else's grant.
     *
     * @throws ServiceAccountDelegationNotFoundException unknown id, another organization, or a
     *                                                   non-null scope that does not match
     */
    ServiceAccountDelegationView revoke(UUID organizationId, UUID actorUserId, UUID delegationId,
                                        UUID serviceAccountUserId, UUID principalUserId);
}
