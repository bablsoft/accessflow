package com.bablsoft.accessflow.engine.neo4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CypherAffectedCountDeriverTest {

    private final CypherQueryParser parser = new CypherQueryParser(TestMessages.keyEcho());

    private String derive(String cypher) {
        return CypherAffectedCountDeriver.derive(parser.parseStatement(cypher));
    }

    @Test
    void derivesCountForMatchWhereSet() {
        assertThat(derive("MATCH (n:Person) WHERE n.age > 30 SET n.flag = true"))
                .isEqualTo("MATCH (n:Person) WHERE n.age > 30 RETURN count(*) AS affected");
    }

    @Test
    void derivesCountForDetachDelete() {
        assertThat(derive("MATCH (u:User) DETACH DELETE u"))
                .isEqualTo("MATCH (u:User) RETURN count(*) AS affected");
    }

    @Test
    void derivesCountForRemove() {
        assertThat(derive("MATCH (u:User) WHERE u.legacy = true REMOVE u.flag"))
                .isEqualTo("MATCH (u:User) WHERE u.legacy = true RETURN count(*) AS affected");
    }

    @Test
    void dropsTrailingReturnWithOrderAndLimit() {
        assertThat(derive("MATCH (u:User) SET u.x = 1 RETURN u ORDER BY u.name LIMIT 5"))
                .isEqualTo("MATCH (u:User) RETURN count(*) AS affected");
    }

    @Test
    void keepsOptionalMatchInThePrefix() {
        assertThat(derive("MATCH (u:User) OPTIONAL MATCH (u)-[:OWNS]->(a:Account) SET u.n = 1"))
                .isEqualTo("MATCH (u:User) OPTIONAL MATCH (u)-[:OWNS]->(a:Account)"
                        + " RETURN count(*) AS affected");
    }

    @Test
    void writeKeywordInsideStringOrBracketsIsIgnored() {
        assertThat(derive("MATCH (u:User) WHERE u.note = 'please DELETE me' SET u.x = 1"))
                .isEqualTo("MATCH (u:User) WHERE u.note = 'please DELETE me'"
                        + " RETURN count(*) AS affected");
    }

    @Test
    void selectIsNotDerived() {
        assertThat(derive("MATCH (u:User) RETURN u")).isNull();
    }

    @Test
    void insertShapesAreNotDerived() {
        assertThat(derive("CREATE (:User {id: 1})")).isNull();
        assertThat(derive("MERGE (u:User {id: 1}) SET u.seen = true")).isNull();
        assertThat(derive("CREATE (u:User) SET u.x = 1")).isNull();
    }

    @Test
    void ddlIsNotDerived() {
        assertThat(derive("DROP INDEX user_id IF EXISTS")).isNull();
    }

    @Test
    void withPipelineBeforeWriteFailsClosed() {
        assertThat(derive("MATCH (u:User) WITH u LIMIT 1 SET u.x = 1")).isNull();
    }

    @Test
    void unwindBeforeWriteFailsClosed() {
        assertThat(derive("UNWIND [1, 2] AS x MATCH (u:User {id: x}) SET u.x = 1")).isNull();
    }

    @Test
    void readResumingAfterWriteFailsClosed() {
        assertThat(derive("MATCH (u:User) SET u.x = 1 WITH u MATCH (v:User) SET v.y = 2")).isNull();
    }

    @Test
    void writeWithoutMatchFailsClosed() {
        assertThat(derive("SET u.x = 1")).isNull();
    }
}
