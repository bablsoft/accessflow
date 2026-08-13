package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * System-actor user primitives for external directory provisioning (SCIM, #621). Unlike
 * {@link UserAdminService} there is no acting user: the self-deactivation and self-demotion
 * guards do not apply, and the caller (the scim module) is responsible for authenticating the
 * organization the operations are scoped to.
 *
 * <p>Deactivation (active {@code true -> false}) publishes
 * {@code core.events.UserDeactivatedEvent} exactly like the admin paths.
 */
public interface ExternalUserDirectoryService {

    /**
     * Create an externally provisioned user.
     *
     * @throws EmailAlreadyExistsException      when the email exists anywhere (emails are
     *                                          globally unique across organizations)
     * @throws ExternalIdAlreadyExistsException when the externalId is taken in this org
     * @throws QuotaExceededException           when the org's user quota is exhausted
     */
    UserView createExternal(CreateExternalUserCommand command);

    /**
     * Partially update an externally managed user. Only SCIM-owned attributes are touched.
     *
     * @throws UserNotFoundException            when the user is not in this organization
     * @throws EmailAlreadyExistsException      when a changed email collides globally
     * @throws ExternalIdAlreadyExistsException when a changed externalId collides in this org
     * @throws QuotaExceededException           when reactivation would exceed the user quota
     */
    UserView updateExternal(UUID organizationId, UUID userId, UpdateExternalUserCommand command);

    Optional<UserView> findById(UUID organizationId, UUID userId);

    Optional<UserView> findByEmail(UUID organizationId, String email);

    Optional<UserView> findByExternalId(UUID organizationId, String scimExternalId);

    /** Offset-based listing ordered by creation time then id; offset is zero-based. */
    DirectoryPage<UserView> list(UUID organizationId, int offset, int limit);
}
