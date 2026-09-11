package com.bablsoft.accessflow.sqlreview.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReviewResultTest {

    private static SqlReviewFinding finding(SqlReviewSeverity severity) {
        return new SqlReviewFinding("rule", severity, 0, null, null);
    }

    @Test
    void notApplicableCarriesNoFindingsAndNeverBlocks() {
        var result = SqlReviewResult.notApplicable();

        assertThat(result.applicable()).isFalse();
        assertThat(result.findings()).isEmpty();
        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    void cleanIsApplicableWithNoFindings() {
        var result = SqlReviewResult.clean();

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    void nullFindingsBecomeEmpty() {
        assertThat(new SqlReviewResult(true, null).findings()).isEmpty();
    }

    @Test
    void findingsAreDefensivelyCopied() {
        var source = new ArrayList<SqlReviewFinding>();
        source.add(finding(SqlReviewSeverity.WARN));
        var result = new SqlReviewResult(true, source);
        source.add(finding(SqlReviewSeverity.BLOCK));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.hasBlocking()).isFalse();
        assertThatThrownBy(() -> result.findings().add(finding(SqlReviewSeverity.BLOCK)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hasBlockingWhenAnyFindingIsBlock() {
        var warnOnly = new SqlReviewResult(true, List.of(finding(SqlReviewSeverity.WARN), finding(SqlReviewSeverity.OFF)));
        var mixed = new SqlReviewResult(true, List.of(finding(SqlReviewSeverity.WARN), finding(SqlReviewSeverity.BLOCK)));

        assertThat(warnOnly.hasBlocking()).isFalse();
        assertThat(mixed.hasBlocking()).isTrue();
    }
}
