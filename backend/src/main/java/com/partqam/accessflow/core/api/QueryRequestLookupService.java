package com.partqam.accessflow.core.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface QueryRequestLookupService {

    Optional<QueryRequestSnapshot> findById(UUID queryRequestId);

    Optional<PendingReviewView> findPendingReview(UUID queryRequestId);

    /**
     * Returns queries in {@code PENDING_REVIEW} where the reviewer is listed (by user id or
     * role) on the datasource's review plan, scoped to their organization, excluding queries
     * the reviewer themselves submitted. The current-stage filter is applied by the caller
     * (workflow module) using the embedded review-plan and decision data.
     */
    Page<PendingReviewView> findPendingForReviewer(UUID organizationId, UUID reviewerUserId,
                                                   UserRoleType role, Pageable pageable);
}
