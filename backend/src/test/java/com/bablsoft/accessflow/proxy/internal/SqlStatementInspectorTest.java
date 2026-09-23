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

    @Test
    void derivedTableAliasDoesNotHideTheRealTable() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT * FROM secret WHERE EXISTS (SELECT 1 FROM (SELECT 1) AS secret)")).tables())
                .containsExactly("secret");
    }

    @Test
    void nonRecursiveCteBodyReadsTheRealTableOfTheSameName() {
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH secret AS (SELECT * FROM secret) SELECT * FROM secret")).tables())
                .containsExactly("secret");
    }

    @Test
    void recursiveCteNameIsVisibleInsideItsOwnBody() {
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3) "
                        + "SELECT * FROM r")).tables()).isEmpty();
    }

    @Test
    void cteDeclaredOnAnOuterDmlStatementHidesItsName() {
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH a AS (SELECT 1) DELETE FROM t WHERE id IN (SELECT * FROM a)")).tables())
                .containsExactly("t");
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH a AS (SELECT 1) INSERT INTO t SELECT * FROM a")).tables())
                .containsExactly("t");
    }

    @Test
    void tableVariableIsNotATable() {
        assertThat(SqlStatementInspector.inspect(parse("SELECT * FROM @tv")).tables()).isEmpty();
    }

    @Test
    void xmlAndJsonTableArgumentsAreWalked() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT * FROM XMLTABLE('/r/v' PASSING (SELECT xmlagg(xmlelement(name v, s)) FROM secret) "
                        + "COLUMNS v text PATH '.') x")).tables()).containsExactly("secret");
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT * FROM JSON_TABLE((SELECT j FROM secret LIMIT 1), '$[*]' "
                        + "COLUMNS (v text PATH '$')) jt")).tables()).containsExactly("secret");
    }

    @Test
    void arraySubscriptAndTopAreWalked() {
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT (ARRAY['x'])[(SELECT count(*) FROM secret)]")).tables())
                .containsExactly("secret");
        assertThat(SqlStatementInspector.inspect(parse(
                "SELECT TOP ((SELECT count(*) FROM secret)) * FROM t")).tables())
                .containsExactlyInAnyOrder("t", "secret");
    }

    @Test
    void expressionModeIncludesColumnQualifiers() {
        assertThat(SqlStatementInspector.tablesIn(
                new net.sf.jsqlparser.schema.Column(new net.sf.jsqlparser.schema.Table("orders"), "id")))
                .containsExactly("orders");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "SELECT ARRAY_AGG(a LIMIT (SELECT count(*) FROM secret)) FROM t",
            "SELECT ANY_VALUE(a HAVING MAX (SELECT s FROM secret LIMIT 1)) FROM t",
            "SELECT max(a) KEEP (DENSE_RANK FIRST ORDER BY (SELECT 1 FROM secret)) FROM t",
            "SELECT GROUP_CONCAT(DISTINCT a ORDER BY (SELECT s FROM secret LIMIT 1) SEPARATOR ',') FROM t",
            "SELECT * FROM t WHERE ((SELECT s FROM secret LIMIT 1) = 1) IS UNKNOWN",
            "SELECT (ARRAY[1,2])[1:(SELECT count(*) FROM secret)] FROM t",
            "SELECT (ARRAY[1,2])[(SELECT 1 FROM secret):2] FROM t",
            "SELECT substring(a FROM (SELECT 1 FROM secret) FOR 2) FROM t"
    })
    void descendsIntoPositionsTheUpstreamFinderSkips(String sql) {
        assertThat(SqlStatementInspector.inspect(parse(sql)).tables())
                .containsExactlyInAnyOrder("t", "secret");
    }

    @Test
    void mergeWithCteHidesTheCteName() {
        assertThat(SqlStatementInspector.inspect(parse(
                "WITH a AS (SELECT 1) MERGE INTO t USING a ON (t.id = a.id) "
                        + "WHEN MATCHED THEN UPDATE SET t.x = 1")).tables())
                .containsExactly("t");
    }
}
