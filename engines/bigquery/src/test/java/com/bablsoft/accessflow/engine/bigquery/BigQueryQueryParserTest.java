package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BigQueryQueryParserTest {

    private final BigQueryQueryParser parser = new BigQueryQueryParser(TestMessages.keyEcho());

    // ---- classification matrix ------------------------------------------------------------------

    @Test
    void classifiesSelectInsertUpdateDelete() {
        assertThat(parser.parse("SELECT * FROM ds.users").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("INSERT INTO ds.users (id) VALUES (1)").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("INSERT ds.users (id) VALUES (1)").type())
                .isEqualTo(QueryType.INSERT); // INTO is optional in GoogleSQL
        assertThat(parser.parse("UPDATE ds.users SET name = 'x' WHERE id = 1").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("DELETE FROM ds.users WHERE id = 1").type())
                .isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("DELETE ds.users WHERE id = 1").type())
                .isEqualTo(QueryType.DELETE); // FROM is optional in GoogleSQL
    }

    @Test
    void mergeClassifiesAsUpdateAndCollectsBothTables() {
        var result = parser.parse(
                "MERGE INTO ds.target t USING ds.source s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.v = s.v");
        assertThat(result.type()).isEqualTo(QueryType.UPDATE);
        assertThat(result.referencedTables()).containsExactlyInAnyOrder("ds.target", "ds.source");
    }

    @Test
    void classifiesDdlForms() {
        assertThat(parser.parse("CREATE TABLE ds.t (id INT64)").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE OR REPLACE TABLE ds.t (id INT64)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE TABLE IF NOT EXISTS ds.t (id INT64)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE VIEW ds.v AS SELECT 1").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE OR REPLACE MATERIALIZED VIEW ds.mv AS SELECT 1").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE SCHEMA staging").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("ALTER TABLE ds.t ADD COLUMN c STRING").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP TABLE IF EXISTS ds.t").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP MATERIALIZED VIEW ds.mv").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("TRUNCATE TABLE ds.t").type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void classifiesWithPrologueByFinalVerb() {
        var select = parser.parse("WITH x AS (SELECT id FROM ds.users) SELECT * FROM x");
        assertThat(select.type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse(
                "WITH doomed AS (SELECT id FROM ds.users WHERE old) "
                        + "DELETE FROM ds.users WHERE id IN (SELECT id FROM doomed)").type())
                .isEqualTo(QueryType.DELETE);
    }

    @Test
    void cteNamesAreExcludedFromReferencedTables() {
        var result = parser.parse(
                "WITH recent (id) AS (SELECT id FROM ds.users) SELECT * FROM recent");
        assertThat(result.referencedTables()).containsExactly("ds.users");
    }

    // ---- rejections -------------------------------------------------------------------------

    @Test
    void rejectsScriptingAndAdminVerbs() {
        for (var sql : new String[]{
                "BEGIN TRANSACTION",
                "DECLARE x INT64",
                "CALL ds.proc()",
                "EXECUTE IMMEDIATE 'SELECT 1'",
                "EXPORT DATA OPTIONS(uri='gs://b/*') AS SELECT 1",
                "LOAD DATA INTO ds.t FROM FILES(uris=['gs://b/f'])",
                "ASSERT (SELECT COUNT(*) FROM ds.t) > 0",
                "GRANT `roles/bigquery.dataViewer` ON TABLE ds.t TO 'user:a@b.c'",
                "REVOKE `roles/bigquery.dataViewer` ON TABLE ds.t FROM 'user:a@b.c'",
                "SET x = 5",
                "IF true THEN SELECT 1 END IF",
                "LOOP SELECT 1 END LOOP",
                "WHILE true DO SELECT 1 END WHILE",
                "RAISE USING MESSAGE = 'no'",
                "RETURN",
                "COMMIT TRANSACTION"}) {
            assertThatThrownBy(() -> parser.parse(sql))
                    .as(sql)
                    .isInstanceOf(InvalidSqlException.class)
                    .hasMessageContaining("unsupported_statement");
        }
    }

    @Test
    void rejectsRoutineIndexModelAndPolicyDdl() {
        for (var sql : new String[]{
                "CREATE PROCEDURE ds.p()",
                "CREATE OR REPLACE FUNCTION ds.f() AS (1)",
                "CREATE TABLE FUNCTION ds.tvf() AS SELECT 1",
                "DROP FUNCTION ds.f",
                "CREATE ROW ACCESS POLICY p ON ds.t GRANT TO ('user:a@b.c') FILTER USING (true)",
                "CREATE SEARCH INDEX i ON ds.t(ALL COLUMNS)",
                "CREATE VECTOR INDEX i ON ds.t(embedding)",
                "CREATE MODEL ds.m OPTIONS(model_type='linear_reg') AS SELECT 1 AS label",
                "CREATE RESERVATION r OPTIONS()",
                "CREATE CAPACITY c OPTIONS()",
                "CREATE ASSIGNMENT a OPTIONS()",
                "CREATE SNAPSHOT TABLE ds.snap CLONE ds.t",
                "ALTER MATERIALIZED SOMETHING"}) {
            assertThatThrownBy(() -> parser.parse(sql))
                    .as(sql)
                    .isInstanceOf(InvalidSqlException.class)
                    .hasMessageContaining("unsupported_statement");
        }
    }

    @Test
    void unsupportedStatementCarriesTheVerb() {
        assertThatThrownBy(() -> parser.parse("CALL ds.proc()"))
                .hasMessageContaining("CALL");
        assertThatThrownBy(() -> parser.parse("CREATE MODEL ds.m AS SELECT 1"))
                .hasMessageContaining("CREATE MODEL");
    }

    @Test
    void rejectsMultipleStatementsButAllowsTrailingSemicolon() {
        assertThatThrownBy(() -> parser.parse("SELECT 1 FROM a; SELECT 2 FROM b"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("multiple_statements");
        assertThat(parser.parse("SELECT * FROM ds.users;").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsBlankAndSemicolonOnlyInput() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> parser.parse(" ; "))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("blank");
    }

    @Test
    void rejectsUnbalancedInput() {
        assertThatThrownBy(() -> parser.parse("SELECT (1 FROM ds.t"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void rejectsUserSuppliedPlaceholders() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM ds.t WHERE id = ?"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("placeholders_forbidden");
        assertThatThrownBy(() -> parser.parse("SELECT * FROM ds.t WHERE id = @id"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("placeholders_forbidden");
    }

    @Test
    void rejectsStatementWithoutRequiredTable() {
        assertThatThrownBy(() -> parser.parse("UPDATE SET x = 1"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("table_required");
        assertThatThrownBy(() -> parser.parse("INSERT INTO (id) VALUES (1)"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("table_required");
        assertThatThrownBy(() -> parser.parse("TRUNCATE TABLE"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("table_required");
        assertThatThrownBy(() -> parser.parse("TRUNCATE ds.t"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("unsupported_statement");
    }

    // ---- referenced tables ----------------------------------------------------------------------

    @Test
    void normalizesTableRefsToLowercaseDotJoinedPaths() {
        assertThat(parser.parse("SELECT * FROM MyProject.DS.Users").referencedTables())
                .containsExactly("myproject.ds.users");
        assertThat(parser.parse("SELECT * FROM `my-project.ds.Users`").referencedTables())
                .containsExactly("my-project.ds.users");
        assertThat(parser.parse("SELECT * FROM `ds`.`Users`").referencedTables())
                .containsExactly("ds.users");
    }

    @Test
    void collectsTablesFromJoinsSubqueriesAndCommaJoins() {
        assertThat(parser.parse("SELECT * FROM ds.a JOIN ds.b ON a.id = b.id").referencedTables())
                .containsExactlyInAnyOrder("ds.a", "ds.b");
        assertThat(parser.parse(
                "SELECT * FROM ds.a WHERE id IN (SELECT id FROM ds.b)").referencedTables())
                .containsExactlyInAnyOrder("ds.a", "ds.b");
        assertThat(parser.parse("SELECT * FROM ds.a, ds.b").referencedTables())
                .containsExactlyInAnyOrder("ds.a", "ds.b");
        assertThat(parser.parse("SELECT * FROM ds.a a2, ds.b AS b2").referencedTables())
                .containsExactlyInAnyOrder("ds.a", "ds.b");
    }

    @Test
    void unnestIsNotATable() {
        assertThat(parser.parse("SELECT * FROM ds.a, UNNEST(tags) AS tag").referencedTables())
                .containsExactly("ds.a");
        assertThat(parser.parse(
                "SELECT * FROM ds.a JOIN UNNEST(a.items) AS item").referencedTables())
                .containsExactly("ds.a");
    }

    @Test
    void detectsWhereAndLimitAtTopLevelOnly() {
        var plain = parser.parse("SELECT * FROM ds.a");
        assertThat(plain.hasWhereClause()).isFalse();
        assertThat(plain.hasLimitClause()).isFalse();
        var full = parser.parse("SELECT * FROM ds.a WHERE id = 1 LIMIT 10");
        assertThat(full.hasWhereClause()).isTrue();
        assertThat(full.hasLimitClause()).isTrue();
        var nested = parser.parse("SELECT * FROM ds.a WHERE id IN (SELECT id FROM ds.b LIMIT 5)");
        assertThat(nested.hasLimitClause()).isFalse();
    }

    // ---- statement facts for the row-security applier ---------------------------------------------

    @Test
    void statementCarriesStructuralFacts() {
        var statement = parser.parseStatement(
                "WITH x AS (SELECT 1) SELECT * FROM ds.a, ds.b WHERE ds.a.id = 1");
        assertThat(statement.hasCte()).isTrue();
        assertThat(statement.hasCommaJoin()).isTrue();
        assertThat(statement.hasWhere()).isTrue();
        assertThat(statement.kind()).isEqualTo(BigQueryStatementKind.SELECT);

        var simple = parser.parseStatement("SELECT * FROM ds.a");
        assertThat(simple.hasCte()).isFalse();
        assertThat(simple.hasCommaJoin()).isFalse();
        assertThat(simple.target().normalized()).isEqualTo("ds.a");
        assertThat(simple.target().lastSegment()).isEqualTo("a");
    }

    @Test
    void targetResolvesPerStatementKind() {
        assertThat(parser.parseStatement("INSERT INTO ds.t (id) VALUES (1)").target().normalized())
                .isEqualTo("ds.t");
        assertThat(parser.parseStatement("UPDATE ds.t SET a = 1 WHERE b = 2").target().normalized())
                .isEqualTo("ds.t");
        assertThat(parser.parseStatement("DELETE FROM ds.t WHERE b = 2").target().normalized())
                .isEqualTo("ds.t");
        assertThat(parser.parseStatement("SELECT 1").target()).isNull();
        assertThat(parser.parseStatement("CREATE TABLE ds.t (id INT64)").target().normalized())
                .isEqualTo("ds.t");
    }

    @Test
    void leadingNonWordFailsClosed() {
        assertThatThrownBy(() -> parser.parse("(SELECT 1) UNION ALL (SELECT 2)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("unsupported_statement");
    }
}
