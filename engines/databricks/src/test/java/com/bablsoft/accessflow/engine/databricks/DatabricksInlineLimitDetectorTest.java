package com.bablsoft.accessflow.engine.databricks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabricksInlineLimitDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {"MAX_RESULT_SIZE_EXCEEDED", "RESULT_SIZE_EXCEEDED", "RESULT_TOO_LARGE",
            "RESULT_SET_TOO_LARGE", "INLINE_RESULT_TOO_LARGE", "max_result_size_exceeded"})
    void recognisesTheOversizeErrorCodes(String code) {
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(
                api("something went wrong", code))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Use disposition EXTERNAL_LINKS to fetch results of this size",
            "The result is too large for the INLINE disposition; use external links",
            "Result size exceeds the maximum allowed for INLINE",
            "response payload too large"})
    void recognisesTheOversizeMessages(String message) {
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(api(message, null))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Table or view not found: main.sales.orders",
            "PARSE_SYNTAX_ERROR near 'FROM'",
            "The query exceeded the maximum execution time",
            "Databricks API returned HTTP 429"})
    void leavesUnrelatedFailuresAlone(String message) {
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(api(message, "SOME_CODE")))
                .isFalse();
    }

    @Test
    void ignoresNullBlankAndTimedOutFailures() {
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(null)).isFalse();
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(api("", null))).isFalse();
        assertThat(DatabricksInlineLimitDetector.inlineLimitExceeded(
                new DatabricksApiException("result too large", null, 0, true))).isFalse();
    }

    @Test
    void unboundedTruncationIsAlwaysASizeCut() {
        // rowLimit == null: the introspection reads, where a row limit cannot explain truncation.
        assertThat(DatabricksInlineLimitDetector.sizeTruncatedInline(true, null, 5_000)).isTrue();
    }

    @Test
    void truncationAtExactlyTheRowLimitIsARowCutNotASizeCut() {
        assertThat(DatabricksInlineLimitDetector.sizeTruncatedInline(true, 101, 101)).isFalse();
        assertThat(DatabricksInlineLimitDetector.sizeTruncatedInline(true, 101, 100)).isTrue();
    }

    @Test
    void anUntruncatedResultIsNeverASizeCut() {
        assertThat(DatabricksInlineLimitDetector.sizeTruncatedInline(false, null, 0)).isFalse();
        assertThat(DatabricksInlineLimitDetector.sizeTruncatedInline(false, 10, 3)).isFalse();
    }

    private static DatabricksApiException api(String message, String errorCode) {
        return new DatabricksApiException(message, errorCode, 400, false);
    }
}
