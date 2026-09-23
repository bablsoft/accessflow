package com.bablsoft.accessflow.proxy.internal;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementInspectorTest {

    @Test
    void readOnlySelectDoesNotWrite() {
        var inspection = SqlStatementInspector.inspect(parse(
                "WITH s AS (SELECT * FROM secret) SELECT * FROM s JOIN t ON t.id = s.id"));

        assertThat(inspection.writesData()).isFalse();
        assertThat(inspection.tables()).containsExactlyInAnyOrder("secret", "t");
    }

    @Test
    void flagsDataModifyingWithItemOfEveryKind() {
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH d AS (DELETE FROM secret RETURNING *) SELECT * FROM d")).writesData()).isTrue();
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH d AS (UPDATE secret SET a = 1 RETURNING *) SELECT * FROM d")).writesData()).isTrue();
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH d AS (INSERT INTO secret (a) VALUES (1) RETURNING *) SELECT * FROM d")).writesData())
                .isTrue();
    }

    @Test
    void dataModifyingWithItemStillReportsItsTable() {
        var inspection = SqlStatementInspector.inspect(parse(
                "WITH d AS (DELETE FROM secret RETURNING *) SELECT count(*) FROM d"));

        assertThat(inspection.tables()).contains("secret");
    }

    @Test
    void flagsSelectIntoTableAndMySqlOutfile() {
        assertThat(SqlStatementInspector.inspect(parse("SELECT * INTO new_t FROM t")).writesData())
                .isTrue();
        assertThat(SqlStatementInspector.inspect(parse("SELECT a INTO OUTFILE '/tmp/x' FROM t"))
                .writesData()).isTrue();
    }

    @Test
    void plainDmlWithoutWithItemIsNotFlagged() {
        assertThat(SqlStatementInspector.inspect(parse("DELETE FROM t WHERE id = 1")).writesData())
                .isFalse();
    }

    @Test
    void descendsIntoWindowPartitionBy() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT rank() OVER (PARTITION BY (SELECT s FROM secret LIMIT 1) ORDER BY a) FROM t"))
                .tables()).containsExactlyInAnyOrder("t", "secret");
    }

    @Test
    void namedWindowWithoutInlineDefinitionIsTraversedSafely() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT sum(a) OVER w FROM t WINDOW w AS (PARTITION BY (SELECT s FROM secret LIMIT 1))"))
                .tables()).containsExactlyInAnyOrder("t", "secret");
    }

    @Test
    void descendsIntoFunctionOrderByLimitAndHaving() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT array_agg(a ORDER BY (SELECT s FROM secret LIMIT 1)) FROM t"))
                .tables()).containsExactlyInAnyOrder("t", "secret");
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT string_agg(a, ',' ORDER BY (SELECT s FROM secret LIMIT 1)) FROM t"))
                .tables()).containsExactlyInAnyOrder("t", "secret");
    }

    @Test
    void descendsIntoKeywordArguments() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT json_object(KEY 'k' VALUE (SELECT s FROM secret LIMIT 1))"))
                .tables()).contains("secret");
    }

    private static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
