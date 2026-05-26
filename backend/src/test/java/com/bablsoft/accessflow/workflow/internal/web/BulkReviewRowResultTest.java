package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.RowStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkReviewRowResultTest {

    @Test
    void fromSuccessRowCopiesDecisionFields() {
        var queryId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var row = RowOutcome.success(queryId,
                new DecisionOutcome(decisionId, DecisionType.APPROVED, QueryStatus.APPROVED, false));

        var result = BulkReviewRowResult.from(row);

        assertThat(result.queryRequestId()).isEqualTo(queryId);
        assertThat(result.status()).isEqualTo(RowStatus.SUCCESS);
        assertThat(result.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(result.decisionId()).isEqualTo(decisionId);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.error()).isNull();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void fromIdempotentReplaySetsReplayFlag() {
        var queryId = UUID.randomUUID();
        var row = RowOutcome.success(queryId,
                new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                        QueryStatus.APPROVED, true));

        var result = BulkReviewRowResult.from(row);

        assertThat(result.idempotentReplay()).isTrue();
    }

    @Test
    void fromFailureRowCopiesErrorFields() {
        var queryId = UUID.randomUUID();
        var row = RowOutcome.failure(queryId, RowStatus.FORBIDDEN,
                "REVIEWER_NOT_ELIGIBLE", "You are not eligible");

        var result = BulkReviewRowResult.from(row);

        assertThat(result.queryRequestId()).isEqualTo(queryId);
        assertThat(result.status()).isEqualTo(RowStatus.FORBIDDEN);
        assertThat(result.errorCode()).isEqualTo("REVIEWER_NOT_ELIGIBLE");
        assertThat(result.error()).isEqualTo("You are not eligible");
        assertThat(result.decision()).isNull();
        assertThat(result.resultingStatus()).isNull();
        assertThat(result.decisionId()).isNull();
        assertThat(result.idempotentReplay()).isNull();
    }
}
