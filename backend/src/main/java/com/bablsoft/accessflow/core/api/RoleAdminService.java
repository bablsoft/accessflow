package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over roles (AF-522). Listing returns the 5 global system roles plus the
 * organization's custom roles. System roles are immutable: update/delete throw
 * {@link SystemRoleImmutableException}; deleting a role still assigned to users throws
 * {@link RoleInUseException}.
 */
public interface RoleAdminService {

    List<RoleView> list(UUID organizationId);

    RoleView get(UUID id, UUID organizationId);

    RoleView create(CreateRoleCommand command);

    RoleView update(UUID id, UUID organizationId, UpdateRoleCommand command);

    void delete(UUID id, UUID organizationId);
}
