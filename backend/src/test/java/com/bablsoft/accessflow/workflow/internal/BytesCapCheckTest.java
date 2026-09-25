package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AppliedBytesCap;
import com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction;
import com.bablsoft.accessflow.core.api.BytesScannedCapOutcome;
import com.bablsoft.accessflow.core.api.BytesScannedCapSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytesCapCheckTest {

    private final AppliedBytesCap cap = new AppliedBytesCap(100, BytesScannedCapSource.DATASOURCE,
            BytesCapMissingEstimateAction.REQUIRE_REVIEW);

    @Test
    void ofComparesTheEstimate() {
        var over = BytesCapCheck.of(cap, 101L);
        assertThat(over.outcome()).isEqualTo(BytesScannedCapOutcome.EXCEEDED);
        assertThat(over.rejects()).isTrue();
        assertThat(over.forcesReview()).isFalse();
        assertThat(over.limit()).isEqualTo(100);
        assertThat(over.estimatedBytes()).isEqualTo(101L);
    }

    @Test
    void aMissingEstimateUnderRequireReviewForcesReviewWithoutRejecting() {
        var check = BytesCapCheck.of(cap, null);
        assertThat(check.forcesReview()).isTrue();
        assertThat(check.rejects()).isFalse();
    }

    @Test
    void anUnevaluatedCapNeitherRejectsNorForcesReview() {
        var check = BytesCapCheck.unevaluated(cap);
        assertThat(check.outcome()).isNull();
        assertThat(check.rejects()).isFalse();
        assertThat(check.forcesReview()).isFalse();
        assertThat(check.source()).isEqualTo(BytesScannedCapSource.DATASOURCE);
    }

    @Test
    void aSourceIsRequired() {
        assertThatThrownBy(() -> new BytesCapCheck(1, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
