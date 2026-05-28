package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
     * Returns the ids of {@code APPROVED} queries that carry a non-null {@code scheduled_for}
     * value at or before {@code now}. The caller (workflow's {@code ScheduledQueryRunJob})
     * iterates these and triggers execution via {@code QueryLifecycleService.executeScheduled}.
     */
    List<UUID> findScheduledDueIds(Instant now);

    /**
     * Returns queries in {@code PENDING_REVIEW} where the reviewer is listed (by user id or
     * role) on the datasource's review plan, scoped to their organization, excluding queries
     * the reviewer themselves submitted. The current-stage filter is applied by the caller
     * (workflow module) using the embedded review-plan and decision data.
     */
    PageResponse<PendingReviewView> findPendingForReviewer(UUID organizationId,
                                                           UUID reviewerUserId,
                                                           UserRoleType role,
                                                           PageRequest pageRequest);

    /**
     * Returns queries in the caller's organization matching the supplied filter, ordered by
     * {@code created_at DESC}.
     */
    PageResponse<QueryListItemView> findForOrganization(QueryListFilter filter,
                                                        PageRequest pageRequest);

    /**
     * Returns the total number of queries matching the supplied filter, scoped to the caller's
     * organization. Used by the CSV export endpoint to decide whether the safety-capped stream
     * will truncate the result set.
     */
    long countForOrganization(QueryListFilter filter);

    /**
     * Streams queries matching the supplied filter to {@code consumer} in {@code created_at DESC}
     * order, stopping once {@code maxRows} have been emitted. The implementation paginates
     * internally so the JPA session is bounded and memory stays flat regardless of result size.
     */
    void streamForOrganization(QueryListFilter filter, int maxRows,
                               Consumer<QueryListItemView> consumer);

    /**
     * Returns the full read-side view of a single query request, including its AI analysis.
     * The query must belong to {@code organizationId} (org-scoped) — otherwise empty.
     */
    Optional<QueryDetailView> findDetailById(UUID queryRequestId, UUID organizationId);

    /**
     * Returns the id of the most recent {@link QueryStatus#EXECUTED} query request matching
     * {@code (submitterId, datasourceId, canonicalSql)}, excluding {@code excludeQueryId}.
     * Used by the workflow lifecycle to link successive runs of the same query (AF-361 —
     * query result diffing). Returns empty when {@code canonicalSql} is {@code null} or no
     * prior run matches.
     */
    Optional<UUID> findPreviousRunId(UUID submitterId, UUID datasourceId,
                                     String canonicalSql, UUID excludeQueryId);
}
