package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of {@code approval_predictions} (issue AF-645), for the query-detail and review-queue
 * consumers. Deliberately split from {@link ApprovalPredictionPersistenceService} so read-only
 * consumers never see the write path.
 */
public interface ApprovalPredictionLookupService {

    Optional<ApprovalPredictionSnapshot> findByQueryRequestId(UUID queryRequestId);

    /** Batch variant for the review queue; null or empty input returns an empty list. */
    List<ApprovalPredictionSnapshot> findByQueryRequestIds(Collection<UUID> queryRequestIds);
}
