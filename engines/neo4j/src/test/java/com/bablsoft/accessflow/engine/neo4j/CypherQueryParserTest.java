package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.engine.neo4j.CypherNodePattern.ClauseKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CypherQueryParserTest {

    private final CypherQueryParser parser = new CypherQueryParser(TestMessages.keyEcho());

    // ---- classification -----------------------------------------------------------------------

    @Test
    void classifiesMatchReturnAsSelect() {
        assertThat(parser.parse("MATCH (u:User) RETURN u").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("RETURN 1").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("SHOW DATABASES").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void classifiesCreateAndMergeAsInsert() {
        assertThat(parser.parse("CREATE (n:User {id: 1})").type()).isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("MERGE (n:User {id: 1})").type()).isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("CREATE p = (a)-[:R]->(b) RETURN p").type()).isEqualTo(QueryType.INSERT);
    }

    @Test
    void classifiesSetAsUpdate() {
        assertThat(parser.parse("MATCH (u:User) SET u.active = true").type()).isEqualTo(QueryType.UPDATE);
    }

    @Test
    void classifiesDeleteRemoveAndDetachDeleteAsDelete() {
        assertThat(parser.parse("MATCH (u:User) DELETE u").type()).isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("MATCH (u:User) DETACH DELETE u").type()).isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("MATCH (u:User) REMOVE u.flag").type()).isEqualTo(QueryType.DELETE);
    }

    @Test
    void deletePrecedesWriteAndSetInMixedClauses() {
        assertThat(parser.parse("MATCH (u:User) SET u.x = 1 DELETE u").type()).isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("MATCH (u:User) CREATE (a:Audit) SET u.x = 1 RETURN u").type())
                .isEqualTo(QueryType.INSERT);
    }

    @Test
    void classifiesSchemaCommandsAsDdl() {
        assertThat(parser.parse("CREATE INDEX user_id FOR (u:User) ON (u.id)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE TEXT INDEX t FOR (u:User) ON (u.name)").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE CONSTRAINT c FOR (u:User) REQUIRE u.id IS UNIQUE").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("DROP INDEX user_id").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("CREATE DATABASE analytics").type()).isEqualTo(QueryType.DDL);
        assertThat(parser.parse("ALTER DATABASE neo4j SET ACCESS READ ONLY").type())
                .isEqualTo(QueryType.DDL);
    }

    @Test
    void dataCreateOfANodeIsNotDdl() {
        var statement = parser.parseStatement("CREATE (n:User {id: 1})");
        assertThat(statement.kind()).isEqualTo(CypherStatementKind.INSERT);
        assertThat(statement.nodePatterns()).isNotEmpty();
    }

    // ---- forbidden constructs -----------------------------------------------------------------

    @Test
    void rejectsLoadCsv() {
        assertThatThrownBy(() -> parser.parse("LOAD CSV FROM 'file:///x.csv' AS row RETURN row"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.neo4j.load_csv_forbidden");
    }

    @Test
    void rejectsDisallowedProcedureCall() {
        assertThatThrownBy(() -> parser.parse("CALL apoc.cypher.runFile('x') YIELD value RETURN value"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.neo4j.procedure_forbidden")
                .hasMessageContaining("apoc.cypher.runFile");
    }

    @Test
    void allowsReadOnlySchemaProcedureAndCallSubquery() {
        assertThat(parser.parse("CALL db.labels() YIELD label RETURN label").type())
                .isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("CALL { MATCH (u:User) RETURN u } RETURN u").type())
                .isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> parser.parse("MATCH (a) RETURN a; MATCH (b) RETURN b"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.neo4j.multiple_statements");
    }

    @Test
    void toleratesTrailingSemicolon() {
        assertThat(parser.parse("MATCH (u:User) RETURN u;").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void rejectsBlankAndUnsupportedLeadingClause() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.neo4j.blank");
        assertThatThrownBy(() -> parser.parse("EXPLAIN ANALYZE foo"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.neo4j.unsupported_statement");
    }

    // ---- references + patterns ----------------------------------------------------------------

    @Test
    void collectsLabelsAndRelationshipTypesButNotMapKeys() {
        var statement = parser.parseStatement(
                "MATCH (u:User)-[:OWNS]->(a:Account {name: 'x'}) RETURN u");
        assertThat(statement.references()).containsExactlyInAnyOrder("user", "owns", "account");
    }

    @Test
    void collectsLabelExpressionAlternatives() {
        var statement = parser.parseStatement("MATCH (n:Admin|Staff) RETURN n");
        assertThat(statement.references()).contains("admin", "staff");
    }

    @Test
    void extractsClauseLevelNodePatternsWithVariableAndKind() {
        var statement = parser.parseStatement("MATCH (u:User) CREATE (a:Audit) RETURN u");
        assertThat(statement.nodePatterns())
                .anySatisfy(p -> {
                    assertThat(p.variable()).isEqualTo("u");
                    assertThat(p.labels()).containsExactly("User");
                    assertThat(p.clauseKind()).isEqualTo(ClauseKind.MATCH);
                })
                .anySatisfy(p -> {
                    assertThat(p.variable()).isEqualTo("a");
                    assertThat(p.labels()).containsExactly("Audit");
                    assertThat(p.clauseKind()).isEqualTo(ClauseKind.WRITE);
                });
    }

    @Test
    void anonymousNodePatternHasNoVariable() {
        var statement = parser.parseStatement("MATCH (:User) RETURN 1");
        assertThat(statement.nodePatterns()).singleElement()
                .satisfies(p -> assertThat(p.hasVariable()).isFalse());
    }

    @Test
    void reportsWhereAndLimitHints() {
        var result = parser.parse("MATCH (u:User) WHERE u.id = 1 RETURN u LIMIT 10");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isTrue();
    }
}
