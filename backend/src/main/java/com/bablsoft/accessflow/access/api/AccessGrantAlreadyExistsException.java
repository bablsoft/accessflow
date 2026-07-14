package com.bablsoft.accessflow.access.api;

import java.util.UUID;

/**
 * Thrown on final approval when the requester already holds a non-expiring (standing/admin) permission
 * on the target resource. JIT grants never silently delete an admin-granted standing permission.
 */
public final class AccessGrantAlreadyExistsException extends AccessException {

    private final AccessResourceKind resourceKind;

    private AccessGrantAlreadyExistsException(AccessResourceKind resourceKind, UUID requesterId,
                                              UUID resourceId, String resourceNoun) {
        super("User " + requesterId + " already holds a standing permission on " + resourceNoun
                + " " + resourceId + "; nothing to grant");
        this.resourceKind = resourceKind;
    }

    public AccessGrantAlreadyExistsException(UUID requesterId, UUID datasourceId) {
        this(AccessResourceKind.DATASOURCE, requesterId, datasourceId, "datasource");
    }

    public static AccessGrantAlreadyExistsException forConnector(UUID requesterId,
                                                                 UUID connectorId) {
        return new AccessGrantAlreadyExistsException(AccessResourceKind.API_CONNECTOR, requesterId,
                connectorId, "API connector");
    }

    public AccessResourceKind resourceKind() {
        return resourceKind;
    }
}
