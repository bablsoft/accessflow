package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultByteEstimatorTest {

    private static final long ROW_OVERHEAD = 32;

    @Test
    void emptyRowCostsOnlyTheRowOverhead() {
        assertThat(ResultByteEstimator.estimateRow(List.of())).isEqualTo(ROW_OVERHEAD);
    }

    @Test
    void primitivesUseFixedSizes() {
        assertThat(ResultByteEstimator.estimateRow(List.of(Boolean.TRUE)))
                .isEqualTo(ROW_OVERHEAD + 1);
        assertThat(ResultByteEstimator.estimateRow(List.of(42)))
                .isEqualTo(ROW_OVERHEAD + 4);
        assertThat(ResultByteEstimator.estimateRow(List.of(1.5f)))
                .isEqualTo(ROW_OVERHEAD + 4);
        assertThat(ResultByteEstimator.estimateRow(List.of(42L)))
                .isEqualTo(ROW_OVERHEAD + 8);
        assertThat(ResultByteEstimator.estimateRow(List.of(1.5d)))
                .isEqualTo(ROW_OVERHEAD + 8);
    }

    @Test
    void nullValueCostsFourBytes() {
        assertThat(ResultByteEstimator.estimateRow(Arrays.asList((Object) null)))
                .isEqualTo(ROW_OVERHEAD + 4);
    }

    @Test
    void stringScalesWithLengthPlusOverhead() {
        assertThat(ResultByteEstimator.estimateRow(List.of("abcde")))
                .isEqualTo(ROW_OVERHEAD + 2 * 5 + 40);
    }

    @Test
    void bigDecimalScalesWithUnscaledMagnitude() {
        var small = ResultByteEstimator.estimateRow(List.of(new BigDecimal("1")));
        var large = ResultByteEstimator.estimateRow(
                List.of(new BigDecimal("1".repeat(100))));
        assertThat(small).isEqualTo(ROW_OVERHEAD + 8);
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void offsetDateTimeUsesFixedSize() {
        var now = OffsetDateTime.of(2026, 7, 16, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(ResultByteEstimator.estimateRow(List.of(now)))
                .isEqualTo(ROW_OVERHEAD + 32);
    }

    @Test
    void listValuesAreEstimatedRecursively() {
        List<Object> nested = List.of(List.of("ab", 1));
        assertThat(ResultByteEstimator.estimateRow(nested))
                .isEqualTo(ROW_OVERHEAD + 16 + (2 * 2 + 40) + 4);
    }

    @Test
    void unknownTypesFallBackToToStringLength() {
        var value = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
        assertThat(ResultByteEstimator.estimateRow(List.of(value)))
                .isEqualTo(ROW_OVERHEAD + 2L * value.toString().length());
    }
}
