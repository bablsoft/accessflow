package com.bablsoft.accessflow.requestgroups.events;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;

import java.util.UUID;

/** Published after each member finishes (or is skipped) during the ordered group run. */
public record RequestGroupItemExecutedEvent(
        UUID requestGroupId, UUID itemId, UUID submitterId, int sequenceOrder,
        RequestGroupItemStatus status) {
}
