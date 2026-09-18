package com.bablsoft.accessflow.requestgroups.api;

import java.util.List;
import java.util.UUID;

/** Create (or replace the members of) a group draft. */
public record CreateRequestGroupCommand(
        UUID organizationId,
        UUID submitterUserId,
        boolean admin,
        String name,
        String description,
        boolean continueOnError,
        List<RequestGroupItemInput> items,
        /** The human an API-key caller acts for (#874); null for a human submission. */
        UUID onBehalfOfUserId) {

    /** Backward-compatible constructor without the #874 on-behalf-of principal. */
    public CreateRequestGroupCommand(UUID organizationId, UUID submitterUserId, boolean admin,
                                     String name, String description, boolean continueOnError,
                                     List<RequestGroupItemInput> items) {
        this(organizationId, submitterUserId, admin, name, description, continueOnError, items, null);
    }
}
