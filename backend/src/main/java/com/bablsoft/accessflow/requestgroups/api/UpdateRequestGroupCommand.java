package com.bablsoft.accessflow.requestgroups.api;

import java.util.List;
import java.util.UUID;

/** Replace the editable fields + full member list of a {@code DRAFT} group. */
public record UpdateRequestGroupCommand(
        UUID requestGroupId,
        UUID organizationId,
        UUID callerUserId,
        boolean admin,
        String name,
        String description,
        boolean continueOnError,
        List<RequestGroupItemInput> items) {
}
