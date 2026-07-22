package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabricksQueryParserTest {

    private final DatabricksQueryParser parser = new DatabricksQueryParser(TestMessages.keyEcho());

    // ---- classification -----------------------------------------------------------------------

    @Test
    void classifiesSelect() {
        var result = parser.parse("SELECT id, name FROM main.sales.orders WHERE id = 1 LIMIT 10");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("main.sales.orders");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isTrue();
    }

    @Test
    void selectWithoutFromIsLegal() {
        var result = parser.parse("SELECT 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).isEmpty();
        assertThat(result.hasWhereClause()).isFalse();
    }

    @Test
    void classifiesInsertIntoAndOverwrite() {
        assertThat(parser.parse("INSERT INTO orders VALUES (1, 'x')").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("INSERT OVERWRITE orders SELECT * FROM staging").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("INSERT INTO TABLE orders VALUES (1)").referencedTables())
                .containsExactly("orders");
        assertThat(parser.parse("INSERT OVERWRITE orders SELECT * FROM staging")
                .referencedTables()).containsExactlyInAnyOrder("orders", "staging");
    }

    @Test
    void classifiesUpdateDeleteAndMerge() {
        assertThat(parser.parse("UPDATE orders SET total = 0 WHERE id = 1").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("DELETE FROM orders WHERE id = 1").type())
                .isEqualTo(QueryType.DELETE);
        var merge = parser.parse("MERGE INTO orders t USING updates s ON t.id = s.id"
                + " WHEN MATCHED THEN UPDATE SET *");
        assertThat(merge.type()).isEqualTo(QueryType.UPDATE);
        assertThat(merge.referencedTables()).containsExactlyInAnyOrder("orders", "updates");
    }

    @Test
    void classifiesDdl() {
        assertThat(parser.parse("CREATE TABLE t (id INT)").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE OR REPLACE TABLE t (id INT)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE OR REPLACE VIEW v AS SELECT 1").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE MATERIALIZED VIEW mv AS SELECT 1").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE SCHEMA IF NOT EXISTS s").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE DATABASE d").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("ALTER TABLE t ADD COLUMN c STRING").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP TABLE IF EXISTS t").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP VIEW v").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("TRUNCATE TABLE t").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("TRUNCATE t").type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void ddlCarriesTheObjectNameAsReferencedTable() {
        assertThat(parser.parse("CREATE TABLE main.sales.orders (id INT)").referencedTables())
                .containsExactly("main.sales.orders");
        assertThat(parser.parse("TRUNCATE TABLE `Sales`.`Orders`").referencedTables())
                .containsExactly("sales.orders");
        assertThat(parser.parse("DROP TABLE IF EXISTS t").referencedTables()).containsExactly("t");
    }

    @Test
    void ctasCollectsBothTargetAndSource() {
        assertThat(parser.parse("CREATE TABLE copy AS SELECT * FROM src").referencedTables())
                .containsExactlyInAnyOrder("copy", "src");
    }

    @Test
    void leadingWithClassifiesByTheFinalVerb() {
        var select = parser.parse(
                "WITH recent AS (SELECT * FROM orders WHERE ts > '2026') SELECT * FROM recent");
        assertThat(select.type()).isEqualTo(QueryType.SELECT);
        assertThat(select.referencedTables()).containsExactly("orders");
        var insert = parser.parse(
                "WITH src AS (SELECT * FROM staging) INSERT INTO orders SELECT * FROM src");
        assertThat(insert.type()).isEqualTo(QueryType.INSERT);
        assertThat(insert.referencedTables()).containsExactlyInAnyOrder("staging", "orders");
    }

    @Test
    void multiCteWithColumnListParses() {
        var result = parser.parse("WITH a (x) AS (SELECT 1), b AS (SELECT * FROM t2)"
                + " SELECT * FROM a JOIN b ON a.x = b.x");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("t2");
    }

    // ---- referenced-table extraction -----------------------------------------------------------

    @Test
    void collectsJoinAndCommaJoinTables() {
        assertThat(parser.parse("SELECT * FROM a JOIN b ON a.id = b.id LEFT JOIN c ON b.id = c.id")
                .referencedTables()).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(parser.parse("SELECT * FROM a x, b y WHERE x.id = y.id").referencedTables())
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void collectsSubqueryTablesButNotAliases() {
        assertThat(parser.parse("SELECT * FROM (SELECT id FROM inner_t) sub WHERE id > 0")
                .referencedTables()).containsExactly("inner_t");
        assertThat(parser.parse("SELECT * FROM t WHERE id IN (SELECT id FROM other)")
                .referencedTables()).containsExactlyInAnyOrder("t", "other");
    }

    @Test
    void normalizesBackticksLowercaseAndDotJoins() {
        assertThat(parser.parse("SELECT * FROM `Main`.`Sales`.`Order Items`").referencedTables())
                .containsExactly("main.sales.order items");
    }

    @Test
    void fromInsideFunctionArgsIsNotATable() {
        assertThat(parser.parse("SELECT extract(day FROM ts) FROM t").referencedTables())
                .containsExactly("t");
        assertThat(parser.parse("SELECT trim(BOTH 'x' FROM name) FROM t").referencedTables())
                .containsExactly("t");
    }

    @Test
    void skipsValuesLateralViewAndCteNames() {
        assertThat(parser.parse("SELECT * FROM VALUES (1), (2) AS v(x)").referencedTables())
                .isEmpty();
        assertThat(parser.parse("SELECT * FROM t LATERAL VIEW explode(arr) e AS item")
                .referencedTables()).containsExactly("t");
    }

    // ---- rejections -----------------------------------------------------------------------------

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.blank");
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.blank");
        assertThatThrownBy(() -> parser.parse(" ; ; ")).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.blank");
    }

    @Test
    void rejectsMultipleStatementsButAllowsTrailingSemicolon() {
        assertThatThrownBy(() -> parser.parse("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.multiple_statements");
        assertThat(parser.parse("SELECT 1;").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsUnbalancedInput() {
        assertThatThrownBy(() -> parser.parse("SELECT (1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unbalanced");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "USE CATALOG main",
            "SET spark.sql.shuffle.partitions = 8",
            "RESET",
            "CACHE TABLE t",
            "UNCACHE TABLE t",
            "COPY INTO t FROM '/mnt/x'",
            "CALL my_proc()",
            "GRANT SELECT ON TABLE t TO `user`",
            "REVOKE SELECT ON TABLE t FROM `user`",
            "MSCK REPAIR TABLE t",
            "ANALYZE TABLE t COMPUTE STATISTICS",
            "OPTIMIZE t",
            "VACUUM t",
            "REFRESH TABLE t",
            "DECLARE v INT",
            "BEGIN",
            "EXECUTE IMMEDIATE 'SELECT 1'",
            "DESCRIBE TABLE t",
            "DESC t",
            "SHOW TABLES",
            "EXPLAIN SELECT 1",
            "LIST '/mnt/x'",
            "CLEAN ROOM x",
            "REPAIR TABLE t",
            "RESTORE TABLE t TO VERSION AS OF 1",
            "CONVERT TO DELTA parquet.`/mnt/x`",
            "FSCK REPAIR TABLE t"})
    void rejectsBannedVerbs(String sql) {
        assertThatThrownBy(() -> parser.parse(sql))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE FUNCTION f() RETURNS INT RETURN 1",
            "DROP FUNCTION f",
            "CREATE VOLUME v",
            "CREATE SHARE s",
            "CREATE RECIPIENT r",
            "CREATE PROVIDER p",
            "CREATE CATALOG c",
            "DROP CATALOG c",
            "CREATE CONNECTION c TYPE mysql",
            "CREATE CREDENTIAL c",
            "CREATE EXTERNAL LOCATION l URL 's3://x'",
            "ALTER LOCATION l",
            "CREATE POLICY p",
            "CREATE USER u",
            "CREATE GROUP g",
            "CREATE SERVICE s",
            "CREATE OR REPLACE FUNCTION f() RETURNS INT RETURN 1"})
    void rejectsBannedDdlObjects(String sql) {
        assertThatThrownBy(() -> parser.parse(sql))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
    }

    @Test
    void offListStatementsFailClosed() {
        assertThatThrownBy(() -> parser.parse("UNPIVOT something"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("(SELECT 1)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("CREATE STREAMING TABLE st AS SELECT 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("CREATE TEMPORARY VIEW v AS SELECT 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("WITH a AS (SELECT 1) DROP TABLE t"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.unsupported_statement");
    }

    @Test
    void rejectsParameterMarkers() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM t WHERE id = ?"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.parameter_marker_forbidden");
        assertThatThrownBy(() -> parser.parse("SELECT * FROM t WHERE id = :id"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.parameter_marker_forbidden");
        assertThatThrownBy(() -> parser.parse("SELECT * FROM t WHERE id IN (:ids)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.parameter_marker_forbidden");
    }

    @Test
    void doubleColonCastAndColonInStringsAreNotMarkers() {
        assertThat(parser.parse("SELECT a::int FROM t").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("SELECT ':name' FROM t").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("SELECT `a:b` FROM t").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsMissingRequiredTable() {
        assertThatThrownBy(() -> parser.parse("INSERT INTO"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("INSERT VALUES (1)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("UPDATE SET x = 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("DELETE WHERE id = 1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("MERGE orders USING u ON 1=1"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("TRUNCATE TABLE"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
        assertThatThrownBy(() -> parser.parse("CREATE TABLE"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.databricks.table_required");
    }

    @Test
    void keywordsInsideStringsAndCommentsDoNotConfuseTheClassifier() {
        var result = parser.parse(
                "SELECT 'DROP TABLE x; --' AS s /* DELETE FROM y */ FROM t -- SHOW TABLES");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("t");
    }
}
