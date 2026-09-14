package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewFindingDetailTest {

    @Test
    void rendersTheMessageThroughTheSuppliedFunction() {
        var finding = new SqlReviewFinding("protected_table", SqlReviewSeverity.WARN, 1, 4,
                Map.of("table", "payroll.salaries", "glob", "payroll.*"));

        var detail = SqlReviewFindingDetail.from(finding, f -> f.args().get("table") + "!");

        assertThat(detail.ruleId()).isEqualTo("protected_table");
        assertThat(detail.severity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(detail.statementIndex()).isEqualTo(1);
        assertThat(detail.lineNumber()).isEqualTo(4);
        assertThat(detail.message()).isEqualTo("payroll.salaries!");
    }

    @Test
    void aNullListRendersAsEmpty() {
        assertThat(SqlReviewFindingDetail.from((List<SqlReviewFinding>) null, f -> "x")).isEmpty();
        assertThat(SqlReviewFindingDetail.from(List.of(), f -> "x")).isEmpty();
    }

    @Test
    void listsKeepOrder() {
        var first = new SqlReviewFinding("a", SqlReviewSeverity.BLOCK, 0, null, Map.of());
        var second = new SqlReviewFinding("b", SqlReviewSeverity.WARN, 0, 3, Map.of());

        var details = SqlReviewFindingDetail.from(List.of(first, second), SqlReviewFinding::ruleId);

        assertThat(details).extracting(SqlReviewFindingDetail::message).containsExactly("a", "b");
        assertThat(details.get(0).lineNumber()).isNull();
    }
}
