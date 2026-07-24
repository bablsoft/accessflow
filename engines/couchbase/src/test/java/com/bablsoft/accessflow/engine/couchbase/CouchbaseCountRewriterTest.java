package com.bablsoft.accessflow.engine.couchbase;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseCountRewriterTest {

    private final CouchbaseQueryParser parser = new CouchbaseQueryParser(TestMessages.keyEcho());

    private String rewrite(String sql) {
        return CouchbaseCountRewriter.toCountStatement(parser.parseStatement(sql));
    }

    // ---- countable shapes ----------------------------------------------------------------------

    @Test
    void deleteWithWhereBecomesCountOverSamePredicate() {
        assertThat(rewrite("DELETE FROM users WHERE team = 'eng'"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users WHERE team = 'eng'");
    }

    @Test
    void deleteWithoutWhereCountsTheWholeKeyspace() {
        assertThat(rewrite("DELETE FROM users"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users");
    }

    @Test
    void updateDropsSetAndReturningClauses() {
        assertThat(rewrite(
                "UPDATE users SET bonus = 1, level = 2 WHERE team = 'eng' RETURNING META(users).id"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users WHERE team = 'eng'");
    }

    @Test
    void updateUnsetIsDroppedToo() {
        assertThat(rewrite("UPDATE users UNSET bonus WHERE team = 'eng'"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users WHERE team = 'eng'");
    }

    @Test
    void preservesTheTargetAliasSoWhereReferencesStillResolve() {
        assertThat(rewrite("UPDATE users AS u SET u.bonus = 1 WHERE u.team = 'eng'"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users AS `u` WHERE u.team = 'eng'");
        assertThat(rewrite("DELETE FROM users u WHERE u.age < 18"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users AS `u` WHERE u.age < 18");
    }

    @Test
    void preservesDottedAndBacktickedKeyspaceSourceText() {
        assertThat(rewrite("DELETE FROM `Bucket-1`.app.`Users` WHERE x = 1"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM `Bucket-1`.app.`Users` WHERE x = 1");
    }

    @Test
    void preservesTheDefaultNamespacePrefix() {
        assertThat(rewrite("DELETE FROM default:users WHERE a = 1"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM default:users WHERE a = 1");
    }

    @Test
    void deleteWithComplexWhereExpressionIsCarriedVerbatim() {
        assertThat(rewrite("DELETE FROM users WHERE (age > 21 AND team = 'eng') OR vip = true"))
                .isEqualTo("SELECT COUNT(*) AS af_count FROM users "
                        + "WHERE (age > 21 AND team = 'eng') OR vip = true");
    }

    // ---- uncountable shapes --------------------------------------------------------------------

    @Test
    void nonMutatingAndNonRewritableKindsAreNull() {
        assertThat(rewrite("SELECT * FROM users")).isNull();
        assertThat(rewrite("INSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})")).isNull();
        assertThat(rewrite("UPSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})")).isNull();
        assertThat(rewrite("CREATE INDEX idx ON users(age)")).isNull();
        assertThat(rewrite("MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                + "WHEN MATCHED THEN UPDATE SET t.a = s.a")).isNull();
    }

    @Test
    void failClosedShapesAreNull() {
        // The same shapes the row-security splicer refuses to rewrite.
        assertThat(rewrite("WITH x AS (SELECT 1) DELETE FROM users WHERE a = 1")).isNull();
        assertThat(rewrite("DELETE FROM users WHERE uid IN (SELECT RAW id FROM admins)")).isNull();
        assertThat(rewrite("UPDATE users SET a = (SELECT RAW b FROM c)[0] WHERE x = 1")).isNull();
        assertThat(rewrite("DELETE FROM users USE KEYS ['k1']")).isNull();
    }

    @Test
    void limitIsNullBecauseItCapsTheMutationCount() {
        assertThat(rewrite("DELETE FROM users WHERE team = 'eng' LIMIT 5")).isNull();
    }
}
