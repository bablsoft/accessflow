package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** Published when an API request transitions to PENDING_REVIEW; consumed by notifications. */
public record ApiRequestReadyForReviewEvent(UUID apiRequestId, int requiredApprovals) {
}
