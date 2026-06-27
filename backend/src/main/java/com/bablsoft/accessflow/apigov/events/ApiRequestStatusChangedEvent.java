package com.bablsoft.accessflow.apigov.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/** Published from the single state-transition chokepoint on every API request status change. */
public record ApiRequestStatusChangedEvent(
        UUID apiRequestId, UUID submitterId, QueryStatus oldStatus, QueryStatus newStatus) {
}
