package com.partqam.accessflow.core.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryRequestLookupService {

    Optional<QueryRequestSnapshot> findById(UUID queryRequestId);

    Optional<PendingReviewView> findPendingReview(UUID queryRequestId);

    /**
     * Returns the ids of {@code PENDING_REVIEW} queries whose {@code created_at + plan
     * approval_timeout_hours} is earlier than {@code now}. The caller (workflow's timeout job)
     * iterates these and calls {@code QueryRequestStateService.markTimedOut} per id.
     */
    List<UUID> findTimedOutPendingReviewIds(Instant now);

    /**
     * Returns queries in {@code PENDING_REVIEW} where the reviewer is listed (by user id or
     * role) on the datasource's review plan, scoped to their organization, excluding queries
     * the reviewer themselves submitted. The current-stage filter is applied by the caller
     * (workflow module) using the embedded review-plan and decision data.
     */
    Page<PendingReviewView> findPendingForReviewer(UUID organizationId, UUID reviewerUserId,
                                                   UserRoleType role, Pageable pageable);

    /**
     * Returns queries in the caller's organization matching the supplied filter, ordered by
     * {@code created_at DESC}.
     */
    Page<QueryListItemView> findForOrganization(QueryListFilter filter, Pageable pageable);

    /**
     * Returns the full read-side view of a single query request, including its AI analysis.
     * The query must belong to {@code organizationId} (org-scoped) — otherwise empty.
     */
    Optional<QueryDetailView> findDetailById(UUID queryRequestId, UUID organizationId);
}
