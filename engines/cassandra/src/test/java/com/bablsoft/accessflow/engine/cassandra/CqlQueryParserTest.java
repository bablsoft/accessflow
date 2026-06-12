package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CqlQueryParserTest {

    private final CqlQueryParser parser = new CqlQueryParser(TestMessages.keyEcho());

    @Test
    void classifiesSelectWithTableAndWhere() {
        var result = parser.parse("SELECT id, name FROM users WHERE id = 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isFalse();
    }

    @Test
    void carriesQualifiedKeyspaceTable() {
        var result = parser.parse("SELECT * FROM app.users WHERE id = 1");
        assertThat(result.referencedTables()).containsExactly("app.users");
        assertThat(parser.parseStatement("SELECT * FROM app.users WHERE id = 1").target())
                .isEqualTo(new CqlTableRef("app", "users"));
    }

    @Test
    void lowercasesUnquotedIdentifiers() {
        assertThat(parser.parseStatement("SELECT * FROM App.Users WHERE id = 1").target())
                .isEqualTo(new CqlTableRef("app", "users"));
    }

    @Test
    void detectsLimitOnReads() {
        assertThat(parser.parse("SELECT * FROM users LIMIT 10").hasLimitClause()).isTrue();
    }

    @Test
    void classifiesInsertUpdateDelete() {
        assertThat(parser.parse("INSERT INTO users (id) VALUES (1)").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("UPDATE users SET name = 'a' WHERE id = 1").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("DELETE FROM users WHERE id = 1").type())
                .isEqualTo(QueryType.DELETE);
    }

    @Test
    void classifiesDdl() {
        assertThat(parser.parse("CREATE TABLE users (id int PRIMARY KEY)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("ALTER TABLE users ADD age int").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP TABLE users").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("TRUNCATE users").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE KEYSPACE app WITH replication = {'class':'SimpleStrategy'}")
                .type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void createIndexCarriesTargetTable() {
        var result = parser.parse("CREATE INDEX ix ON users (name)");
        assertThat(result.type()).isEqualTo(QueryType.DDL);
        assertThat(result.referencedTables()).contains("users");
    }

    @Test
    void rejectsBatch() {
        assertThatThrownBy(() -> parser.parse(
                "BEGIN BATCH INSERT INTO users (id) VALUES (1); APPLY BATCH"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.batch_forbidden");
    }

    @Test
    void rejectsUserDefinedFunctionsAndAggregates() {
        assertThatThrownBy(() -> parser.parse(
                "CREATE FUNCTION f(i int) RETURNS NULL ON NULL INPUT RETURNS int "
                        + "LANGUAGE java AS 'return i;'"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.udf_forbidden");
        assertThatThrownBy(() -> parser.parse("CREATE OR REPLACE FUNCTION f(i int) "
                + "RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java AS 'return i;'"))
                .hasMessageContaining("error.cassandra.udf_forbidden");
        assertThatThrownBy(() -> parser.parse("DROP AGGREGATE avg_state"))
                .hasMessageContaining("error.cassandra.udf_forbidden");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM users; SELECT * FROM orders"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.multiple_statements");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.blank");
    }

    @Test
    void rejectsUnsupportedStatements() {
        assertThatThrownBy(() -> parser.parse("USE app"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.cassandra.unsupported_statement");
        assertThatThrownBy(() -> parser.parse("CREATE TRIGGER t ON users USING 'X'"))
                .hasMessageContaining("error.cassandra.unsupported_statement");
    }

    @Test
    void toleratesTrailingSemicolonAndComments() {
        var result = parser.parse("-- read users\nSELECT * FROM users WHERE id = 1;");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
    }
}
