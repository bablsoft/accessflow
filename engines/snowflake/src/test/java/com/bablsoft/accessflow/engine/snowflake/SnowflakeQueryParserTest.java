package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeQueryParserTest {

    private final SnowflakeQueryParser parser = new SnowflakeQueryParser(TestMessages.keyEcho());

    // ---- classification ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "SELECT * FROM users, SELECT",
            "(SELECT * FROM users), SELECT",
            "INSERT INTO users VALUES (1), INSERT",
            "INSERT OVERWRITE INTO users SELECT * FROM staging, INSERT",
            "UPDATE users SET active = false WHERE id = 1, UPDATE",
            "DELETE FROM users WHERE id = 1, DELETE",
            "MERGE INTO users USING staging ON users.id = staging.id WHEN MATCHED THEN UPDATE SET a = 1, UPDATE",
            "TRUNCATE users, DDL",
            "TRUNCATE TABLE IF EXISTS users, DDL",
            "CREATE TABLE t (id INT), DDL",
            "CREATE OR REPLACE TABLE t (id INT), DDL",
            "CREATE OR REPLACE VIEW v AS SELECT 1, DDL",
            "CREATE TRANSIENT TABLE t (id INT), DDL",
            "CREATE TEMPORARY TABLE t (id INT), DDL",
            "CREATE TEMP TABLE t (id INT), DDL",
            "CREATE MATERIALIZED VIEW mv AS SELECT 1, DDL",
            "CREATE SCHEMA analytics, DDL",
            "CREATE DATABASE IF NOT EXISTS reporting, DDL",
            "ALTER TABLE t ADD COLUMN c INT, DDL",
            "DROP VIEW IF EXISTS v, DDL",
            "DROP TABLE t, DDL",
    })
    void classifiesAllowedStatements(String sql, QueryType expected) {
        assertThat(parser.parse(sql).type()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CALL my_proc()",
            "EXECUTE IMMEDIATE $$ SELECT 1 $$",
            "BEGIN",
            "DECLARE x INT",
            "PUT file:///tmp/x @stage",
            "GET @stage file:///tmp",
            "COPY INTO t FROM @stage",
            "UNLOAD ('SELECT 1')",
            "USE WAREHOUSE wh",
            "SHOW TABLES",
            "DESCRIBE TABLE t",
            "DESC TABLE t",
            "GRANT SELECT ON t TO ROLE r",
            "REVOKE SELECT ON t FROM ROLE r",
            "UNDROP TABLE t",
            "COMMENT ON TABLE t IS 'x'",
            "SET v = 1",
            "UNSET v",
            "LIST @stage",
            "REMOVE @stage/file.csv",
            "EXPLAIN SELECT 1",
    })
    void rejectsForbiddenVerbs(String sql) {
        assertThatThrownBy(() -> parser.parse(sql))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE PROCEDURE p() RETURNS INT LANGUAGE SQL AS $$ SELECT 1 $$",
            "CREATE OR REPLACE PROCEDURE p() RETURNS INT LANGUAGE SQL AS $$ SELECT 1 $$",
            "CREATE FUNCTION f() RETURNS INT AS $$ 1 $$",
            "CREATE TASK t1 AS SELECT 1",
            "CREATE STREAM s ON TABLE t",
            "CREATE PIPE p AS COPY INTO t FROM @stage",
            "CREATE STAGE my_stage",
            "CREATE WAREHOUSE wh",
            "CREATE USER u",
            "CREATE ROLE r",
            "CREATE INTEGRATION i",
            "CREATE SHARE s",
            "ALTER WAREHOUSE wh SUSPEND",
            "DROP TASK t1",
            "ALTER SESSION SET TIMEZONE = 'UTC'",
    })
    void rejectsForbiddenDdlObjects(String sql) {
        assertThatThrownBy(() -> parser.parse(sql))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
    }

    @Test
    void classifiesCteByFinalVerb() {
        var select = parser.parse("WITH top AS (SELECT id FROM orders) SELECT * FROM top");
        assertThat(select.type()).isEqualTo(QueryType.SELECT);

        var recursive = parser.parse(
                "WITH RECURSIVE r (n) AS (SELECT 1) SELECT * FROM r");
        assertThat(recursive.type()).isEqualTo(QueryType.SELECT);

        var multi = parser.parse(
                "WITH a AS (SELECT 1), b (x) AS (SELECT 2) SELECT * FROM a JOIN b ON 1 = 1");
        assertThat(multi.type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void cteStatementRecordsHasCteAndExcludesCteNamesFromTables() {
        var statement = parser.parseStatement(
                "WITH top AS (SELECT id FROM orders) SELECT * FROM top");
        assertThat(statement.hasCte()).isTrue();
        assertThat(statement.tables()).containsExactly("orders");
    }

    @Test
    void malformedCteListIsRejected() {
        assertThatThrownBy(() -> parser.parse("WITH top SELECT 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("WITH top AS (SELECT 1)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
    }

    // ---- referenced tables ---------------------------------------------------------------------

    @Test
    void collectsQualifiedLowercasedDotJoinedTables() {
        var result = parser.parse("SELECT * FROM Analytics.PUBLIC.Users u JOIN db2.s2.Orders o ON u.id = o.uid");
        assertThat(result.referencedTables())
                .containsExactlyInAnyOrder("analytics.public.users", "db2.s2.orders");
    }

    @Test
    void stripsQuotesFromQuotedIdentifiers() {
        var result = parser.parse("SELECT * FROM \"My Schema\".\"My Table\"");
        assertThat(result.referencedTables()).containsExactly("my schema.my table");
    }

    @Test
    void collectsTablesFromSubselects() {
        var result = parser.parse("SELECT * FROM (SELECT * FROM inner_t) x");
        assertThat(result.referencedTables()).containsExactly("inner_t");
    }

    @Test
    void collectsCommaSeparatedFromList() {
        var result = parser.parse("SELECT * FROM a, b AS bee, c WHERE a.id = b.id");
        assertThat(result.referencedTables()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void skipsFunctionStyleAndLateralRefs() {
        assertThat(parser.parse("SELECT * FROM TABLE(GENERATOR(ROWCOUNT => 10))")
                .referencedTables()).isEmpty();
        assertThat(parser.parse("SELECT * FROM my_func(1)").referencedTables()).isEmpty();
        assertThat(parser.parse("SELECT v.x FROM t, LATERAL FLATTEN(input => t.j) v")
                .referencedTables()).containsExactly("t");
        assertThat(parser.parse("SELECT * FROM VALUES (1), (2)").referencedTables()).isEmpty();
    }

    @Test
    void fromInsideScalarFunctionIsNotATable() {
        var result = parser.parse("SELECT EXTRACT(month FROM created_at) FROM events");
        assertThat(result.referencedTables()).containsExactly("events");
    }

    @Test
    void collectsWriteTargets() {
        assertThat(parser.parse("INSERT INTO s.t (a, b) VALUES (1, 2)").referencedTables())
                .containsExactly("s.t");
        assertThat(parser.parse("UPDATE t SET a = 1").referencedTables()).containsExactly("t");
        assertThat(parser.parse("DELETE FROM t USING o WHERE t.id = o.id").referencedTables())
                .containsExactlyInAnyOrder("t", "o");
        assertThat(parser.parse(
                "MERGE INTO t USING src ON t.id = src.id WHEN MATCHED THEN UPDATE SET a = 1")
                .referencedTables()).containsExactlyInAnyOrder("t", "src");
        assertThat(parser.parse("TRUNCATE TABLE s.t").referencedTables()).containsExactly("s.t");
        assertThat(parser.parse("DROP TABLE d.s.t").referencedTables()).containsExactly("d.s.t");
    }

    @Test
    void insertSelectReferencesBothTables() {
        var result = parser.parse("INSERT INTO target SELECT * FROM source");
        assertThat(result.referencedTables()).containsExactlyInAnyOrder("target", "source");
    }

    // ---- flags, targets ------------------------------------------------------------------------

    @Test
    void reportsWhereAndLimitFlags() {
        var bare = parser.parse("SELECT * FROM t");
        assertThat(bare.hasWhereClause()).isFalse();
        assertThat(bare.hasLimitClause()).isFalse();

        var filtered = parser.parse("SELECT * FROM t WHERE id = 1 LIMIT 10");
        assertThat(filtered.hasWhereClause()).isTrue();
        assertThat(filtered.hasLimitClause()).isTrue();

        var subqueryOnly = parser.parse("SELECT * FROM (SELECT * FROM t WHERE id = 1) x");
        assertThat(subqueryOnly.hasWhereClause()).isFalse();
    }

    @Test
    void selectWithoutFromHasNoTargetAndNoTables() {
        var statement = parser.parseStatement("SELECT CURRENT_TIMESTAMP()");
        assertThat(statement.target()).isNull();
        assertThat(statement.tables()).isEmpty();
    }

    @Test
    void targetIsTheMainQueryFromNotASubqueryFrom() {
        var statement = parser.parseStatement(
                "SELECT (SELECT MAX(x) FROM other) FROM main_t WHERE id = 1");
        assertThat(statement.target().normalized()).isEqualTo("main_t");
    }

    // ---- rejections ------------------------------------------------------------------------------

    @Test
    void rejectsBlankAndNull() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.blank");
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.blank");
        assertThatThrownBy(() -> parser.parse(" ; ")).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.blank");
    }

    @Test
    void rejectsMultipleStatementsButAllowsTrailingSemicolon() {
        assertThat(parser.parse("SELECT 1;").type()).isEqualTo(QueryType.SELECT);
        assertThatThrownBy(() -> parser.parse("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.multiple_statements");
        assertThatThrownBy(() -> parser.parse("DELETE FROM t; DROP TABLE t;"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.multiple_statements");
    }

    @Test
    void semicolonInsideStringOrDollarQuoteIsNotAStatementSeparator() {
        assertThat(parser.parse("SELECT 'a;b' FROM t").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("SELECT $$a;b$$ FROM t").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsUnbalancedInput() {
        assertThatThrownBy(() -> parser.parse("SELECT (1 FROM t"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unbalanced");
    }

    @Test
    void rejectsPlaceholders() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM t WHERE id = ?"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.placeholders_forbidden");
        assertThatThrownBy(() -> parser.parse("SELECT * FROM t WHERE id IN (?, ?)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.placeholders_forbidden");
    }

    @Test
    void rejectsMissingTargetTable() {
        assertThatThrownBy(() -> parser.parse("DELETE FROM"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.table_required");
        assertThatThrownBy(() -> parser.parse("UPDATE SET a = 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.table_required");
        assertThatThrownBy(() -> parser.parse("TRUNCATE TABLE"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.table_required");
        assertThatThrownBy(() -> parser.parse("CREATE TABLE"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.table_required");
    }

    @Test
    void rejectsNonWordFirstTokenAndParenthesizedNonSelect() {
        assertThatThrownBy(() -> parser.parse("123"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("(DELETE FROM t)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
    }

    @Test
    void createOrWithoutReplaceIsRejected() {
        assertThatThrownBy(() -> parser.parse("CREATE OR TABLE t (id INT)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.snowflake.unsupported_statement");
    }

    @Test
    void parseStatementKeepsOriginalSqlAndTokens() {
        var sql = "SELECT * FROM t WHERE id = 1";
        var statement = parser.parseStatement(sql);
        assertThat(statement.sql()).isEqualTo(sql);
        assertThat(statement.kind()).isEqualTo(SnowflakeStatementKind.SELECT);
        assertThat(statement.hasWhere()).isTrue();
        assertThat(statement.tokens()).isNotEmpty();
    }
}
