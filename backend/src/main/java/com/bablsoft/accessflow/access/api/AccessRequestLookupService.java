package com.bablsoft.accessflow.access.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only cross-module lookup of a single access request. Consumed by the notifications, realtime,
 * and audit modules to enrich their payloads without reaching into {@code access.internal}.
 */
public interface AccessRequestLookupService {

    Optional<AccessRequestView> findById(UUID accessRequestId);

    /**
     * The user ids eligible to review the request at its datasource's lowest approval stage
     * (datasource-scoped reviewers when configured), excluding the requester. Used to fan out the
     * "new access request" notification to reviewers. Empty when the request or its plan is unknown.
     */
    Set<UUID> findReviewerRecipients(UUID accessRequestId);
}
