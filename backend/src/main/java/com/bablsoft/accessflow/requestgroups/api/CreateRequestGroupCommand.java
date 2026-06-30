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
        List<RequestGroupItemInput> items) {
}
