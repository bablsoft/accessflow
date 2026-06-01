package com.bablsoft.accessflow.access.api;

import java.util.UUID;

/**
 * Thrown on final approval when the requester already holds a non-expiring (standing/admin) permission
 * on the datasource. JIT grants never silently delete an admin-granted standing permission.
 */
public final class AccessGrantAlreadyExistsException extends AccessException {

    public AccessGrantAlreadyExistsException(UUID requesterId, UUID datasourceId) {
        super("User " + requesterId + " already holds a standing permission on datasource "
                + datasourceId + "; nothing to grant");
    }
}
