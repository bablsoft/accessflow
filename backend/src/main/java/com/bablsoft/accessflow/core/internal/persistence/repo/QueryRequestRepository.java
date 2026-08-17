package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryRequestRepository
        extends JpaRepository<QueryRequestEntity, UUID>,
                JpaSpecificationExecutor<QueryRequestEntity>,
                QueryRequestStatsRepository {

    Page<QueryRequestEntity> findAllByDatasource_Id(UUID datasourceId, Pageable pageable);

    Page<QueryRequestEntity> findAllBySubmittedBy_Id(UUID userId, Pageable pageable);

    Page<QueryRequestEntity> findAllByStatus(QueryStatus status, Pageable pageable);

    List<QueryRequestEntity> findAllByStatus(QueryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueryRequestEntity q where q.id = :id")
    Optional<QueryRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    // AF-456: rolling-window query count for an org's per-day quota. Scoped through the datasource's
    // organization since query_requests has no direct org FK.
    @Query("""
            select count(q) from QueryRequestEntity q
             where q.datasource.organization.id = :orgId
               and q.createdAt >= :since
            """)
    long countByOrganizationSince(@Param("orgId") UUID organizationId, @Param("since") Instant since);

    /**
     * Pending rows the reviewer may plausibly act on.
     *
     * <p>{@code principalIds} / {@code lowerRoleNames} carry the caller's own identity plus any
     * borrowed through an out-of-office delegation (#622), so this query is a deliberate
     * over-approximation — it cannot tell which identity satisfied which branch. DefaultReviewService
     * re-checks every row per-identity before it reaches the user; do not "optimise away" that pass.
     *
     * <p>{@code userId} excludes only the caller's own submissions and must stay a scalar. Widening
     * it to exclude their delegators as well would hide requests the reviewer is eligible for in
     * their own right; that exclusion is per-identity and lives in the in-memory re-check.
     *
     * <p>The approver match is an {@code exists} subquery rather than a join: a join fans out one
     * row per matching approver rule, which is why this once needed {@code select distinct} and a
     * {@code count(distinct q)} count query. With a collection on both sides the fan-out would
     * multiply, and the count would still be wrong.
     *
     * <p>Both collections always contain the caller's own values, so neither is ever empty.
     */
    @Query("""
            select q from QueryRequestEntity q
              join q.datasource d
              join d.reviewPlan rp
            where q.status = :status
              and d.organization.id = :orgId
              and q.submittedBy.id <> :userId
              and exists (
                select 1 from ReviewPlanApproverEntity rpa
                 where rpa.reviewPlan = rp
                   and (rpa.user.id in :principalIds or lower(rpa.role) in :lowerRoleNames)
              )
              and (
                not exists (select 1 from DatasourceReviewerEntity dr where dr.datasource = d)
                or exists (
                  select 1 from DatasourceReviewerEntity dr
                   where dr.datasource = d and dr.user.id in :principalIds
                )
                or exists (
                  select 1 from DatasourceReviewerEntity dr
                    join UserGroupMembershipEntity m on m.group = dr.group
                   where dr.datasource = d and m.user.id in :principalIds
                )
              )
            """)
    Page<QueryRequestEntity> findPendingForReviewer(@Param("orgId") UUID orgId,
                                                    @Param("userId") UUID userId,
                                                    @Param("principalIds") Collection<UUID> principalIds,
                                                    @Param("lowerRoleNames") Collection<String> lowerRoleNames,
                                                    @Param("status") QueryStatus status,
                                                    Pageable pageable);

    @Query(value = """
            SELECT q.id
            FROM query_requests q
            JOIN datasources d ON q.datasource_id = d.id
            JOIN review_plans rp ON d.review_plan_id = rp.id
            WHERE q.status = 'PENDING_REVIEW'::query_status
              AND q.created_at + (rp.approval_timeout_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findTimedOutPendingReviewIds(@Param("now") Instant now);

    /**
     * Requests past their plan's {@code escalation_after_hours} that have not been escalated yet
     * (#622). Backed by idx_query_requests_escalation_scan. A null column means the plan has
     * escalation switched off, so it never matches.
     */
    @Query(value = """
            SELECT q.id
            FROM query_requests q
            JOIN datasources d ON d.id = q.datasource_id
            JOIN review_plans rp ON rp.id = d.review_plan_id
            WHERE q.status = 'PENDING_REVIEW'::query_status
              AND q.escalated_at IS NULL
              AND rp.escalation_after_hours IS NOT NULL
              AND q.created_at + (rp.escalation_after_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findEscalationDueIds(@Param("now") Instant now);

    /**
     * Requests due a nudge (#622): never nudged and past one interval since submission, or nudged
     * longer than one interval ago. Backed by idx_query_requests_nudge_scan.
     */
    @Query(value = """
            SELECT q.id
            FROM query_requests q
            JOIN datasources d ON d.id = q.datasource_id
            JOIN review_plans rp ON rp.id = d.review_plan_id
            WHERE q.status = 'PENDING_REVIEW'::query_status
              AND rp.nudge_interval_hours IS NOT NULL
              AND COALESCE(q.last_nudged_at, q.created_at)
                  + (rp.nudge_interval_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findNudgeDueIds(@Param("now") Instant now);

    @Query(value = """
            SELECT q.id
            FROM query_requests q
            WHERE q.status = 'APPROVED'::query_status
              AND q.scheduled_for IS NOT NULL
              AND q.scheduled_for <= :now
            """, nativeQuery = true)
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    // #627: due recurring-series parents. The partial index idx_query_requests_recurrence_due
    // (V132) keeps this a cheap scan over active series only.
    @Query(value = """
            SELECT q.id
            FROM query_requests q
            WHERE q.status = 'APPROVED'::query_status
              AND q.recurrence_next_run_at IS NOT NULL
              AND q.recurrence_next_run_at <= :now
            """, nativeQuery = true)
    List<UUID> findRecurringDueIds(@Param("now") Instant now);

    // #627: occurrence history of a recurring series, org-scoped, newest first (the caller
    // supplies the sort via Pageable so Spring can page it).
    @Query("""
            select q from QueryRequestEntity q
             where q.recurringParentId = :parentId
               and q.datasource.organization.id = :orgId
            """)
    Page<QueryRequestEntity> findOccurrences(@Param("parentId") UUID parentId,
                                             @Param("orgId") UUID organizationId,
                                             Pageable pageable);

    @Query("""
            select q.id from QueryRequestEntity q
             where q.status = :status
               and q.submittedBy.id = :submitterId
               and q.datasource.id = :datasourceId
               and q.canonicalSql = :canonicalSql
               and q.id <> :excludeId
             order by q.executionCompletedAt desc
            """)
    List<UUID> findPreviousExecutedRunIds(@Param("status") QueryStatus status,
                                          @Param("submitterId") UUID submitterId,
                                          @Param("datasourceId") UUID datasourceId,
                                          @Param("canonicalSql") String canonicalSql,
                                          @Param("excludeId") UUID excludeId,
                                          Pageable pageable);

    // AF-446: most recent approval-time of the requester's prior queries on the same datasource
    // (status APPROVED or EXECUTED), used by the time-since-last-approval routing condition.
    // updatedAt is the @Version column, which equals the approve / execute transition time.
    @Query("""
            select max(q.updatedAt) from QueryRequestEntity q
             where q.datasource.organization.id = :orgId
               and q.submittedBy.id = :userId
               and q.datasource.id = :datasourceId
               and q.status in :statuses
               and (:excludingQueryId is null or q.id <> :excludingQueryId)
            """)
    Optional<Instant> findLastApprovalInstant(@Param("orgId") UUID organizationId,
                                              @Param("userId") UUID userId,
                                              @Param("datasourceId") UUID datasourceId,
                                              @Param("statuses") Collection<QueryStatus> statuses,
                                              @Param("excludingQueryId") UUID excludingQueryId);

    // AF-649: the approval-outcome training population — human-decided queries only. Terminal
    // APPROVED/EXECUTED/REJECTED must carry at least one review_decisions row (only the human
    // review path ever writes one, so this excludes routing auto-approve/auto-reject and — unless
    // a partial human review preceded the ticket resolution, an accepted noise edge — external-
    // ticket decisions); TIMED_OUT is the one legitimate zero-decision negative. Grant-covered
    // auto-approvals and break-glass submissions are excluded explicitly; CANCELLED and FAILED
    // fall outside the status list. The enum values are bound as parameters (filled by the
    // default methods below) — enum literals in JPQL render as casts to a nonexistent PG type.
    String APPROVAL_OUTCOME_DECIDED_PREDICATE = """
             where q.datasource.organization.id = :orgId
               and q.createdAt >= :since
               and q.approvedByGrantId is null
               and q.submissionReason <> :excludedReason
               and (q.status = :timedOut
                    or (q.status in :humanDecidedStatuses
                        and exists (select 1 from ReviewDecisionEntity rd where rd.queryRequest = q)))
            """;

    List<QueryStatus> APPROVAL_OUTCOME_HUMAN_DECIDED_STATUSES =
            List.of(QueryStatus.APPROVED, QueryStatus.EXECUTED, QueryStatus.REJECTED);

    List<QueryStatus> APPROVAL_OUTCOME_APPROVED_STATUSES =
            List.of(QueryStatus.APPROVED, QueryStatus.EXECUTED);

    // AF-649: one row per decided query with the feature columns the extractor needs. The joins go
    // through the bare-UUID back-pointers so they pick the query's current analysis / estimate row,
    // not historical reanalyses. Column order is mirrored by the mapper in
    // DefaultApprovalOutcomeHistoryLookupService — keep them in sync.
    @Query("""
            select q.id, q.queryType, q.transactional, q.createdAt,
                   q.submittedBy.id, q.datasource.id, q.status,
                   ai.riskScore, ai.riskLevel, ai.issues, ai.failed,
                   est.estimatedRows, est.affectedRowCount, est.estimatedCost, est.scanType,
                   est.supported, est.failed
              from QueryRequestEntity q
              left join AiAnalysisEntity ai on ai.id = q.aiAnalysisId
              left join QueryEstimateEntity est on est.id = q.queryEstimateId
            """ + APPROVAL_OUTCOME_DECIDED_PREDICATE + """
             order by q.createdAt desc
            """)
    List<Object[]> findApprovalOutcomeSampleRows(
            @Param("orgId") UUID organizationId,
            @Param("since") Instant since,
            @Param("excludedReason") SubmissionReason excludedReason,
            @Param("timedOut") QueryStatus timedOut,
            @Param("humanDecidedStatuses") Collection<QueryStatus> humanDecidedStatuses,
            Pageable pageable);

    default List<Object[]> findApprovalOutcomeSampleRows(UUID organizationId, Instant since,
                                                         Pageable pageable) {
        return findApprovalOutcomeSampleRows(organizationId, since,
                SubmissionReason.EMERGENCY_ACCESS, QueryStatus.TIMED_OUT,
                APPROVAL_OUTCOME_HUMAN_DECIDED_STATUSES, pageable);
    }

    @Query("""
            select count(q),
                   coalesce(sum(case when q.status in :approvedStatuses then 1 else 0 end), 0)
              from QueryRequestEntity q
            """ + APPROVAL_OUTCOME_DECIDED_PREDICATE + """
               and q.submittedBy.id = :userId
            """)
    List<Object[]> countApprovalOutcomesBySubmitter(
            @Param("orgId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("since") Instant since,
            @Param("excludedReason") SubmissionReason excludedReason,
            @Param("timedOut") QueryStatus timedOut,
            @Param("humanDecidedStatuses") Collection<QueryStatus> humanDecidedStatuses,
            @Param("approvedStatuses") Collection<QueryStatus> approvedStatuses);

    default List<Object[]> countApprovalOutcomesBySubmitter(UUID organizationId, UUID userId,
                                                            Instant since) {
        return countApprovalOutcomesBySubmitter(organizationId, userId, since,
                SubmissionReason.EMERGENCY_ACCESS, QueryStatus.TIMED_OUT,
                APPROVAL_OUTCOME_HUMAN_DECIDED_STATUSES, APPROVAL_OUTCOME_APPROVED_STATUSES);
    }

    @Query("""
            select count(q),
                   coalesce(sum(case when q.status in :approvedStatuses then 1 else 0 end), 0)
              from QueryRequestEntity q
            """ + APPROVAL_OUTCOME_DECIDED_PREDICATE + """
               and q.datasource.id = :datasourceId
            """)
    List<Object[]> countApprovalOutcomesByDatasource(
            @Param("orgId") UUID organizationId,
            @Param("datasourceId") UUID datasourceId,
            @Param("since") Instant since,
            @Param("excludedReason") SubmissionReason excludedReason,
            @Param("timedOut") QueryStatus timedOut,
            @Param("humanDecidedStatuses") Collection<QueryStatus> humanDecidedStatuses,
            @Param("approvedStatuses") Collection<QueryStatus> approvedStatuses);

    default List<Object[]> countApprovalOutcomesByDatasource(UUID organizationId,
                                                             UUID datasourceId, Instant since) {
        return countApprovalOutcomesByDatasource(organizationId, datasourceId, since,
                SubmissionReason.EMERGENCY_ACCESS, QueryStatus.TIMED_OUT,
                APPROVAL_OUTCOME_HUMAN_DECIDED_STATUSES, APPROVAL_OUTCOME_APPROVED_STATUSES);
    }
}
