package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytesScannedCapRecordsTest {

    @Test
    void anEstimateAboveTheCapExceedsIt() {
        var cap = new AppliedBytesCap(100, BytesScannedCapSource.DATASOURCE,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);

        assertThat(cap.check(101L)).isEqualTo(BytesScannedCapOutcome.EXCEEDED);
        assertThat(cap.check(100L)).isEqualTo(BytesScannedCapOutcome.WITHIN);
        assertThat(cap.check(0L)).isEqualTo(BytesScannedCapOutcome.WITHIN);
    }

    @Test
    void aMissingEstimateFollowsTheDatasourcePolicy() {
        var review = new AppliedBytesCap(100, BytesScannedCapSource.GRANT,
                BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        var reject = new AppliedBytesCap(100, BytesScannedCapSource.GRANT,
                BytesCapMissingEstimateAction.REJECT);

        assertThat(review.check(null)).isEqualTo(BytesScannedCapOutcome.NO_ESTIMATE_REVIEW);
        assertThat(reject.check(null)).isEqualTo(BytesScannedCapOutcome.NO_ESTIMATE_REJECTED);
    }

    @Test
    void anAbsentPolicyDefaultsToRequireReview() {
        var cap = new AppliedBytesCap(1, BytesScannedCapSource.DATASOURCE, null);

        assertThat(cap.missingEstimate()).isEqualTo(BytesCapMissingEstimateAction.REQUIRE_REVIEW);
    }

    @Test
    void aCapMustBePositiveAndNamed() {
        assertThatThrownBy(() -> new AppliedBytesCap(0, BytesScannedCapSource.DATASOURCE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppliedBytesCap(1, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void onlyTheTwoRefusingOutcomesReject() {
        assertThat(BytesScannedCapOutcome.EXCEEDED.rejects()).isTrue();
        assertThat(BytesScannedCapOutcome.NO_ESTIMATE_REJECTED.rejects()).isTrue();
        assertThat(BytesScannedCapOutcome.WITHIN.rejects()).isFalse();
        assertThat(BytesScannedCapOutcome.NO_ESTIMATE_REVIEW.rejects()).isFalse();
    }

    @Test
    void onlyTheBytesReportingWarehousesSupportACap() {
        assertThat(BytesScannedCapSupport.supports(DbType.BIGQUERY)).isTrue();
        assertThat(BytesScannedCapSupport.supports(DbType.SNOWFLAKE)).isTrue();
        assertThat(BytesScannedCapSupport.supports(DbType.DATABRICKS)).isTrue();
        assertThat(BytesScannedCapSupport.supports(DbType.POSTGRESQL)).isFalse();
        assertThat(BytesScannedCapSupport.supports(DbType.MONGODB)).isFalse();
        assertThat(BytesScannedCapSupport.supports(null)).isFalse();
        assertThat(BytesScannedCapSupport.supportedTypes()).hasSize(3);
    }

    @Test
    void theExceptionsCarryWhatTheirHandlersRender() {
        var notSupported = new BytesScannedCapNotSupportedException(DbType.POSTGRESQL);
        assertThat(notSupported.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(notSupported).hasMessageContaining("POSTGRESQL");

        var cap = new AppliedBytesCap(10, BytesScannedCapSource.GRANT, null);
        var exceeded = new BytesScannedCapExceededException("too big", cap, 20L,
                BytesScannedCapOutcome.EXCEEDED);
        assertThat(exceeded).hasMessage("too big");
        assertThat(exceeded.cap()).isEqualTo(cap);
        assertThat(exceeded.estimatedBytes()).isEqualTo(20L);
        assertThat(exceeded.outcome()).isEqualTo(BytesScannedCapOutcome.EXCEEDED);
    }

    @Test
    void byteSizesRenderInDecimalUnitsWithTheExactFigure() {
        assertThat(ByteSizeFormat.format(0)).isEqualTo("0 B");
        assertThat(ByteSizeFormat.format(999)).isEqualTo("999 B");
        assertThat(ByteSizeFormat.format(1_000)).isEqualTo("1 KB (1000 B)");
        assertThat(ByteSizeFormat.format(1_500_000_000_000L))
                .isEqualTo("1.5 TB (1500000000000 B)");
        assertThat(ByteSizeFormat.format(1_234_567_890L)).isEqualTo("1.23 GB (1234567890 B)");
        assertThat(ByteSizeFormat.format(Long.MAX_VALUE)).startsWith("9.22 EB");
    }
}
