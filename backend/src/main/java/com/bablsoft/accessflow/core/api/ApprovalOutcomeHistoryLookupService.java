package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the approval-outcome training data (issue AF-645): extracts the organization's
 * historical, <em>human-decided</em> query requests for the {@code ai} module's predictor.
 *
 * <p>Label rules — a query is part of the training population when it reached a terminal decided
 * status through human review:
 * <ul>
 *   <li>positive: {@code APPROVED} / {@code EXECUTED} with at least one {@code review_decisions}
 *       row;</li>
 *   <li>negative: {@code REJECTED} with at least one {@code review_decisions} row, or
 *       {@code TIMED_OUT} (the one legitimate zero-decision negative);</li>
 *   <li>excluded: {@code CANCELLED}; {@code FAILED} (a post-approval execution error — out of the
 *       label spec, which keys on the review outcome statuses only); grant-covered auto-approvals
 *       ({@code approved_by_grant_id} set); break-glass submissions
 *       ({@code submission_reason = EMERGENCY_ACCESS}); routing-policy auto-approve / auto-reject
 *       and external-ticket decisions — both are terminal {@code APPROVED} / {@code REJECTED}
 *       with zero {@code review_decisions} rows, since only the human review path ever writes
 *       one.</li>
 * </ul>
 * Auto paths carry no reviewer judgment and would poison the model. One accepted edge: an
 * external-ticket resolution that lands after a partial human review (an earlier-stage approval
 * or a changes-requested decision) leaves a decision row behind and is therefore counted as
 * human-decided even though the terminal transition was machine-attributed — bounded noise the
 * schema cannot distinguish without a decision-provenance column.
 */
public interface ApprovalOutcomeHistoryLookupService {

    /**
     * Returns up to {@code maxRows} decided samples for the organization, newest-first, with the
     * feature columns the extractor needs. {@code organizationId} and {@code since} must be
     * non-null; {@code maxRows <= 0} returns an empty list.
     */
    List<ApprovalOutcomeSample> findDecidedSamples(UUID organizationId, Instant since, int maxRows);

    /** Decided / approved counts for one submitter over the same labeled population. */
    ApprovalRateCounts submitterCounts(UUID organizationId, UUID userId, Instant since);

    /** Decided / approved counts for one datasource over the same labeled population. */
    ApprovalRateCounts datasourceCounts(UUID organizationId, UUID datasourceId, Instant since);
}
