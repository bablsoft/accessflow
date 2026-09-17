package com.bablsoft.accessflow.serviceaccounts.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin lifecycle of service accounts and of the API keys issued on their behalf (#871, epic #867).
 * Everything is scoped to the caller's organization: an account in another organization is
 * indistinguishable from a missing one ({@link ServiceAccountNotFoundException}, never a 403).
 *
 * <p>The bootstrap reconciler and this service coexist by field ownership. On a
 * {@link ServiceAccountSource#BOOTSTRAP} account the <em>declared</em> fields — display name, role
 * and the {@code bootstrapDeclared} key — belong to the YAML: changing one is
 * {@link ServiceAccountBootstrapManagedException}, revoking or rotating the declared key is
 * {@link ServiceAccountKeyBootstrapDeclaredException}. Everything else (active flag, description,
 * owner, tool allow-list, rate limits, additional keys) is UI-owned on both kinds of account and
 * survives a reconcile.
 */
public interface ServiceAccountAdminService {

    /**
     * The organization's service accounts, newest first (only {@code createdAt} is sortable — the
     * user columns live in another module and are joined after paging). {@code managedBy} filters
     * when non-null. Views carry no key list, only the active-key count and last use.
     */
    PageResponse<ServiceAccountAdminView> list(UUID organizationId, ServiceAccountSource managedBy,
                                               PageRequest pageRequest);

    /**
     * One account with its full key list, newest key first.
     *
     * @throws ServiceAccountNotFoundException when missing, in another organization, or a human
     */
    ServiceAccountAdminView get(UUID organizationId, UUID userId);

    /**
     * Mints the {@code users} row (unusable password hash, {@code LOCAL}, default role
     * {@code READONLY}), types it through {@code ServiceAccountProvisioningService.ensureRegistered}
     * as {@code UI}-managed and sets the UI-owned fields. No key is issued.
     *
     * @throws ServiceAccountOwnerInvalidException  when the owner is not an active human of the org
     * @throws ServiceAccountUnknownMcpToolException when an allow-list entry is not a catalog tool
     */
    ServiceAccountAdminView create(UUID organizationId, CreateServiceAccountCommand command);

    /**
     * Every field is null-means-unchanged; UI-owned fields are reset only through
     * {@code command.clear()}, so an omitted field can never widen anything.
     *
     * @throws ServiceAccountBootstrapManagedException when a declared field would change on a
     *                                                  BOOTSTRAP account (an equal value is a no-op)
     */
    ServiceAccountAdminView update(UUID organizationId, UUID userId, UUID actorUserId,
                                   UpdateServiceAccountCommand command);

    /**
     * Soft-deactivates the account (same fan-out as a human). Its keys are not revoked — the API-key
     * filter rejects an inactive user, and reactivating restores them. Idempotent.
     */
    void deactivate(UUID organizationId, UUID userId, UUID actorUserId);

    /**
     * Issues a key owned by the account; the plaintext is returned once and never persisted.
     *
     * @throws ServiceAccountKeyNameConflictException when the account already has a key of that name
     */
    ServiceAccountIssuedKey issueKey(UUID organizationId, UUID userId, IssueServiceAccountKeyCommand command);

    /**
     * Issues the replacement and sets the superseded key's expiry to {@code now + grace} (or leaves
     * it earlier if it already expired sooner), never touching {@code revokedAt}: the old key keeps
     * authenticating until the window elapses.
     *
     * @throws ServiceAccountKeyNotFoundException          when the key is not the account's
     * @throws ServiceAccountKeyBootstrapDeclaredException when it is the bootstrap-declared key
     * @throws ServiceAccountKeyRevokedException           when it is already revoked
     * @throws ServiceAccountKeyNameConflictException      when the replacement name is taken
     */
    ServiceAccountRotatedKey rotateKey(UUID organizationId, UUID userId, UUID keyId,
                                       RotateServiceAccountKeyCommand command);

    /**
     * Revokes the key immediately. Idempotent.
     *
     * @throws ServiceAccountKeyNotFoundException          when the key is not the account's
     * @throws ServiceAccountKeyBootstrapDeclaredException when it is the bootstrap-declared key —
     *                                                     a changed reconcile would reactivate it
     */
    void revokeKey(UUID organizationId, UUID userId, UUID keyId);
}
