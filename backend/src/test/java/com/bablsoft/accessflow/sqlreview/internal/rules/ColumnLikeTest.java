package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.DateUnitExpression;
import net.sf.jsqlparser.schema.Column;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupportExpressions.expression;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnLikeTest {

    @ParameterizedTest
    @ValueSource(strings = {"year", "month", "day", "hour", "minute", "second"})
    void bareDateUnitIdentifiersAreUnqualifiedColumns(String name) {
        var parsed = expression(name);
        // Pins the JSqlParser 5.4 behaviour this helper exists for; if it reverts to Column, drop the adapter.
        assertThat(parsed).isInstanceOf(DateUnitExpression.class);
        assertThat(ColumnLike.isColumn(parsed)).isTrue();
        assertThat(ColumnLike.qualifier(parsed)).isNull();
    }

    @Test
    void columnsReportTheirQualifier() {
        assertThat(expression("t.year")).isInstanceOf(Column.class);
        assertThat(ColumnLike.isColumn(expression("t.year"))).isTrue();
        assertThat(ColumnLike.qualifier(expression("T.year"))).isEqualTo("t");
        assertThat(ColumnLike.isColumn(expression("x"))).isTrue();
        assertThat(ColumnLike.qualifier(expression("x"))).isNull();
    }

    @Test
    void nonColumnsAreNotColumns() {
        assertThat(ColumnLike.isColumn(expression("1"))).isFalse();
        assertThat(ColumnLike.isColumn(expression("f(x)"))).isFalse();
        assertThat(ColumnLike.isColumn(null)).isFalse();
        assertThat(ColumnLike.qualifier(expression("'a'"))).isNull();
    }
}
