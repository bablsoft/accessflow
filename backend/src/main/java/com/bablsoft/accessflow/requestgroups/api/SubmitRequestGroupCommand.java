package com.bablsoft.accessflow.requestgroups.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Submit a {@code DRAFT} group for AI + review. When {@code breakGlass} is set the submitter must
 * hold {@code can_break_glass} on every member target; the group bypasses AI + review and is
 * force-approved. {@code scheduledFor} (nullable) defers the ordered run to a future timestamp.
 */
public record SubmitRequestGroupCommand(
        UUID requestGroupId,
        UUID organizationId,
        UUID callerUserId,
        boolean admin,
        boolean breakGlass,
        Instant scheduledFor,
        String submittedIp,
        String submittedUserAgent) {
}
