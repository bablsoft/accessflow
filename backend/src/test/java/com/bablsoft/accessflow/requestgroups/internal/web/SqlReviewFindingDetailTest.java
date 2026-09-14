package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewFindingDetailTest {

    @Test
    void rendersTheMessageThroughTheSuppliedFunctionAndCopiesEveryField() {
        var finding = new SqlReviewFinding("protected_table", SqlReviewSeverity.BLOCK, 2, 5,
                Map.of("table", "payroll.salaries", "glob", "payroll.*"));

        var detail = SqlReviewFindingDetail.from(finding, f -> "touches " + f.args().get("table"));

        assertThat(detail.ruleId()).isEqualTo("protected_table");
        assertThat(detail.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(detail.statementIndex()).isEqualTo(2);
        assertThat(detail.lineNumber()).isEqualTo(5);
        assertThat(detail.message()).isEqualTo("touches payroll.salaries");
    }

    @Test
    void keepsAnUnknownLineNumberNull() {
        var finding = new SqlReviewFinding("select_star", SqlReviewSeverity.WARN, 0, null, Map.of());

        assertThat(SqlReviewFindingDetail.from(finding, SqlReviewFinding::ruleId).lineNumber()).isNull();
    }
}
