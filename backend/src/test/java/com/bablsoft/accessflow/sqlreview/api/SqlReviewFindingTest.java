package com.bablsoft.accessflow.sqlreview.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReviewFindingTest {

    @Test
    void holdsFieldValues() {
        var finding = new SqlReviewFinding("select_star", SqlReviewSeverity.WARN, 2, 7, Map.of("table", "users"));

        assertThat(finding.ruleId()).isEqualTo("select_star");
        assertThat(finding.severity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(finding.statementIndex()).isEqualTo(2);
        assertThat(finding.lineNumber()).isEqualTo(7);
        assertThat(finding.args()).containsExactlyEntriesOf(Map.of("table", "users"));
    }

    @Test
    void nullArgsBecomeEmptyAndLineNumberMayBeNull() {
        var finding = new SqlReviewFinding("truncate_statement", SqlReviewSeverity.BLOCK, 0, null, null);

        assertThat(finding.args()).isEmpty();
        assertThat(finding.lineNumber()).isNull();
    }

    @Test
    void argsAreDefensivelyCopied() {
        var source = new HashMap<String, String>();
        source.put("k", "v");
        var finding = new SqlReviewFinding("r", SqlReviewSeverity.WARN, 0, null, source);
        source.put("later", "x");

        assertThat(finding.args()).containsOnlyKeys("k");
        assertThatThrownBy(() -> finding.args().put("z", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ruleIdAndSeverityAreRequired() {
        assertThatThrownBy(() -> new SqlReviewFinding(null, SqlReviewSeverity.WARN, 0, null, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("ruleId");
        assertThatThrownBy(() -> new SqlReviewFinding("r", null, 0, null, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("severity");
    }

    @Test
    void onlyBlockSeverityIsBlocking() {
        assertThat(new SqlReviewFinding("r", SqlReviewSeverity.BLOCK, 0, null, null).isBlocking()).isTrue();
        assertThat(new SqlReviewFinding("r", SqlReviewSeverity.WARN, 0, null, null).isBlocking()).isFalse();
        assertThat(new SqlReviewFinding("r", SqlReviewSeverity.OFF, 0, null, null).isBlocking()).isFalse();
    }
}
