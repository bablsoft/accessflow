package com.bablsoft.accessflow.access.events;

import java.util.UUID;

/** Published when a user submits a new access-grant request. Reviewers are notified. */
public record AccessRequestSubmittedEvent(UUID accessRequestId, UUID requesterId) {
}
