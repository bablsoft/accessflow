package com.bablsoft.accessflow.requestgroups.events;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;

import java.util.UUID;

/** Published from the single state-transition chokepoint on every group status change. */
public record RequestGroupStatusChangedEvent(
        UUID requestGroupId, UUID submitterId, RequestGroupStatus oldStatus,
        RequestGroupStatus newStatus) {
}
