package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitor;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementWalkerTest {

    @Test
    void collectsFunctionsLikesSelectBodiesAndTablesAcrossTheWholeStatement() {
        var walker = StatementWalker.walk(parse(
                "SELECT upper(a) FROM t JOIN u ON u.n LIKE '%q' "
                        + "WHERE id IN (SELECT id FROM v WHERE k ILIKE '%z' OR benchmark(1, sleep(2)) > 0) "
                        + "HAVING count(*) > 1"));
        assertThat(walker.functions()).extracting(Function::getName)
                .containsExactly("upper", "benchmark", "sleep", "count");
        assertThat(walker.likes()).hasSize(2);
        assertThat(walker.plainSelects()).hasSize(2);
        // TablesNamesFinder visits a join's from-item and right-item, which are the same object, so
        // a joined table is recorded twice; anchoring only needs the first occurrence.
        assertThat(walker.tables()).extracting(Table::getName).containsExactly("t", "u", "u", "v");
    }

    @Test
    void reachesOrderByGroupByLimitAndOffsetExpressions() {
        // JSqlParser 5.3 only takes a literal or parameter as the LIMIT row count; OFFSET accepts an
        // expression, and the walker covers both slots the same way.
        var select = StatementWalker.walk(parse(
                "SELECT id FROM t GROUP BY lower(k) ORDER BY pg_sleep(10) LIMIT 5 OFFSET sleep(1)"));
        assertThat(select.functions()).extracting(Function::getName)
                .containsExactly("lower", "pg_sleep", "sleep");
        var ordered = StatementWalker.walk(parse("SELECT id FROM t ORDER BY CASE WHEN k LIKE '%x' THEN 1 END"));
        assertThat(ordered.likes()).hasSize(1);
        assertThat(StatementWalker.walk(parse("UPDATE t SET a = 1 ORDER BY sleep(1) LIMIT 1")).functions())
                .extracting(Function::getName).containsExactly("sleep");
        assertThat(StatementWalker.walk(parse("DELETE FROM t ORDER BY sleep(1) LIMIT 1")).functions())
                .extracting(Function::getName).containsExactly("sleep");
        assertThat(StatementWalker.walk(parse("SELECT id FROM t LIMIT 5 OFFSET 2")).functions()).isEmpty();
    }

    @Test
    void walksDmlToo() {
        var update = StatementWalker.walk(parse("UPDATE t SET a = load_file('/x') WHERE b LIKE '%y'"));
        assertThat(update.functions()).extracting(Function::getName).containsExactly("load_file");
        assertThat(update.likes()).hasSize(1);
        assertThat(update.tables()).extracting(Table::getName).containsExactly("t");
        var insert = StatementWalker.walk(parse("INSERT INTO t (a) SELECT pg_sleep(5) FROM u"));
        assertThat(insert.functions()).extracting(Function::getName).containsExactly("pg_sleep");
        assertThat(insert.plainSelects()).hasSize(1);
    }

    @Test
    void resultsAreReadOnlyAndUnsupportedShapesYieldWhatWasCollected() {
        var walker = StatementWalker.walk(parse("SELECT 1"));
        assertThatThrownBy(() -> walker.functions().add(null)).isInstanceOf(UnsupportedOperationException.class);
        Statement exotic = new Statement() {
            @Override
            public <T, S> T accept(StatementVisitor<T> visitor, S context) {
                throw new UnsupportedOperationException("exotic");
            }
        };
        var partial = StatementWalker.walk(exotic);
        assertThat(partial.functions()).isEmpty();
        assertThat(partial.likes()).isEmpty();
        assertThat(partial.plainSelects()).isEmpty();
        assertThat(partial.tables()).isEmpty();
    }
}
