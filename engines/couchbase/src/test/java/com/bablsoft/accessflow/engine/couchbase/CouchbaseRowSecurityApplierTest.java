package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchbaseRowSecurityApplierTest {

    private final CouchbaseQueryParser parser = new CouchbaseQueryParser(TestMessages.keyEcho());
    private final CouchbaseRowSecurityApplier applier =
            new CouchbaseRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective directive(String tableRef, String column,
                                                  RowSecurityOperator operator, List<Object> values) {
        return new RowSecurityDirective(UUID.randomUUID(), tableRef, column, operator, values);
    }

    private CouchbaseRowSecurityApplier.Applied apply(String sql, RowSecurityDirective... ds) {
        return applier.apply(parser.parseStatement(sql), List.of(ds));
    }

    private RowSecurityClassification classify(String sql, RowSecurityDirective... ds) {
        return applier.classify("couchbase", parser.parseStatement(sql), List.of(ds));
    }

    // ---- no-op paths ---------------------------------------------------------------------------

    @Test
    void noDirectivesIsANoop() {
        var applied = apply("SELECT * FROM users");
        assertThat(applied.sql()).isEqualTo("SELECT * FROM users");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void isNullOperatorSplicesUnaryIsNull() {
        var applied = apply("SELECT * FROM users",
                directive("users", "deleted_at", RowSecurityOperator.IS_NULL, List.of()));
        assertThat(applied.sql()).contains("IS NULL");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void nonMatchingDirectiveIsANoop() {
        var applied = apply("SELECT * FROM users",
                directive("orders", "region", RowSecurityOperator.EQUALS, List.of("emea")));
        assertThat(applied.sql()).isEqualTo("SELECT * FROM users");
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void ddlOnPoliciedKeyspaceIsUnaffected() {
        var applied = apply("CREATE INDEX idx ON users(age)",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(applied.sql()).isEqualTo("CREATE INDEX idx ON users(age)");
    }

    // ---- WHERE splice --------------------------------------------------------------------------

    @Test
    void addsWhereWhenAbsent() {
        var d = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        var applied = apply("SELECT name FROM users", d);
        assertThat(applied.sql())
                .isEqualTo("SELECT name FROM users WHERE (`users`.`team` = $af_rls_0) ");
        assertThat(applied.parameters()).containsEntry("af_rls_0", "eng");
        assertThat(applied.appliedPolicyIds()).containsExactly(d.policyId());
    }

    @Test
    void andsIntoExistingWhereWrappedInParens() {
        var applied = apply("SELECT name FROM users WHERE age > 21 ORDER BY name",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(applied.sql()).isEqualTo(
                "SELECT name FROM users WHERE (age > 21) AND (`users`.`team` = $af_rls_0) "
                        + "ORDER BY name");
    }

    @Test
    void insertsBeforeTailClausesWhenNoWhere() {
        var applied = apply("SELECT name FROM users ORDER BY name LIMIT 5",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(applied.sql()).isEqualTo(
                "SELECT name FROM users WHERE (`users`.`team` = $af_rls_0) ORDER BY name LIMIT 5");
    }

    @Test
    void qualifiesColumnsWithTheFromAlias() {
        var applied = apply("SELECT u.name FROM users AS u WHERE u.age > 21",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(applied.sql()).contains("(`u`.`team` = $af_rls_0)");
    }

    @Test
    void rewritesUpdateAndDelete() {
        var update = apply("UPDATE users SET bonus = 1 WHERE team = 'eng' RETURNING META(users).id",
                directive("users", "region", RowSecurityOperator.NOT_EQUALS, List.of("emea")));
        assertThat(update.sql()).isEqualTo(
                "UPDATE users SET bonus = 1 WHERE (team = 'eng') AND "
                        + "(`users`.`region` != $af_rls_0) RETURNING META(users).id");

        var delete = apply("DELETE FROM users",
                directive("users", "age", RowSecurityOperator.LESS_THAN, List.of(18)));
        assertThat(delete.sql()).isEqualTo(
                "DELETE FROM users WHERE (`users`.`age` < $af_rls_0) ");
        assertThat(delete.parameters()).containsEntry("af_rls_0", 18);
    }

    @Test
    void combinesMultipleDirectivesWithAnd() {
        var applied = apply("SELECT * FROM users",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")),
                directive("users", "level", RowSecurityOperator.GREATER_THAN_OR_EQUAL, List.of(3)));
        assertThat(applied.sql()).contains(
                "WHERE (`users`.`team` = $af_rls_0 AND `users`.`level` >= $af_rls_1)");
        assertThat(applied.parameters())
                .containsEntry("af_rls_0", "eng")
                .containsEntry("af_rls_1", 3);
        assertThat(applied.appliedPolicyIds()).hasSize(2);
    }

    @Test
    void bindsInOperatorsAsArrayParameters() {
        var in = apply("SELECT * FROM users",
                directive("users", "team", RowSecurityOperator.IN, List.of("eng", "sales")));
        assertThat(in.sql()).contains("`users`.`team` IN $af_rls_0");
        assertThat(in.parameters()).containsEntry("af_rls_0", List.of("eng", "sales"));

        var notIn = apply("SELECT * FROM users",
                directive("users", "team", RowSecurityOperator.NOT_IN, List.of("x")));
        assertThat(notIn.sql()).contains("`users`.`team` NOT IN $af_rls_0");
    }

    @Test
    void coversAllScalarOperators() {
        assertThat(apply("SELECT * FROM users",
                directive("users", "a", RowSecurityOperator.LESS_THAN_OR_EQUAL, List.of(1))).sql())
                .contains("`users`.`a` <= $af_rls_0");
        assertThat(apply("SELECT * FROM users",
                directive("users", "a", RowSecurityOperator.GREATER_THAN, List.of(1))).sql())
                .contains("`users`.`a` > $af_rls_0");
    }

    @Test
    void emptyValuesFailClosedAsFalse() {
        var applied = apply("SELECT * FROM users",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of()));
        assertThat(applied.sql()).contains("WHERE (FALSE)");
        assertThat(applied.parameters()).isEmpty();
    }

    @Test
    void escapesBackticksInColumnNames() {
        var applied = apply("SELECT * FROM users",
                directive("users", "wei`rd", RowSecurityOperator.EQUALS, List.of(1)));
        assertThat(applied.sql()).contains("`users`.`wei``rd` = $af_rls_0");
    }

    @Test
    void matchesDirectiveByLastSegmentOfDottedRefs() {
        var applied = apply("SELECT * FROM bucket1.app.users",
                directive("travel.inventory.users", "team", RowSecurityOperator.EQUALS,
                        List.of("eng")));
        assertThat(applied.sql()).contains("WHERE (`users`.`team` = $af_rls_0)");
    }

    // ---- fail-closed shapes ---------------------------------------------------------------------

    @Test
    void rejectsInsertAndUpsertIntoPoliciedKeyspace() {
        var d = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        assertThatThrownBy(() -> apply("INSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})", d))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_couchbase_insert_unsupported");
        assertThatThrownBy(() -> apply("UPSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})", d))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_couchbase_insert_unsupported");
    }

    @Test
    void rejectsInsertSelectReadingPoliciedKeyspace() {
        assertThatThrownBy(() -> apply(
                "INSERT INTO archive (KEY META(u).id, VALUE u) SELECT u FROM users u",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_couchbase_unrewritable");
    }

    @Test
    void rejectsPoliciedMerge() {
        assertThatThrownBy(() -> apply(
                "MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_couchbase_unrewritable");
    }

    @Test
    void rejectsUnrewritableSelectShapes() {
        var d = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        for (var sql : new String[]{
                "WITH x AS (SELECT t.* FROM users t) SELECT * FROM x",
                "SELECT * FROM orders WHERE uid IN (SELECT RAW id FROM users)",
                "SELECT * FROM orders o JOIN users u ON o.uid = META(u).id",
                "SELECT * FROM orders o UNNEST o.items i JOIN users u ON i.uid = META(u).id",
                "SELECT * FROM users USE KEYS ['k1']",
                "SELECT * FROM users UNION SELECT * FROM admins"}) {
            assertThatThrownBy(() -> apply(sql, d))
                    .as(sql)
                    .isInstanceOf(UnrewritableRowSecurityException.class)
                    .hasMessageContaining("error.row_security_couchbase_unrewritable");
        }
    }

    // ---- offline classification (AF-630) --------------------------------------------------------

    @Test
    void classifyReportsNotApplicableWhenNoDirectiveTargetsTheStatement() {
        var result = classify("SELECT * FROM users",
                directive("orders", "region", RowSecurityOperator.EQUALS, List.of("emea")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("couchbase");
        assertThat(result.appliedPolicyIds()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsNotApplicableForNoDirectivesAtAll() {
        assertThat(classify("SELECT * FROM users").outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyReportsNotApplicableForDdlOnAPoliciedKeyspace() {
        var result = classify("CREATE INDEX idx ON users(age)",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyReportsAppliedWithThePolicyIdsThatWouldTakeEffect() {
        var first = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        var second = directive("travel.inventory.users", "level",
                RowSecurityOperator.GREATER_THAN_OR_EQUAL, List.of(3));
        var ignored = directive("orders", "region", RowSecurityOperator.EQUALS, List.of("emea"));
        var result = classify("SELECT name FROM users WHERE age > 21", first, second, ignored);
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds())
                .containsExactlyInAnyOrder(first.policyId(), second.policyId());
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsAppliedForUpdateAndDelete() {
        assertThat(classify("UPDATE users SET bonus = 1",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"))).outcome())
                .isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(classify("DELETE FROM users WHERE age < 18",
                directive("users", "team", RowSecurityOperator.IN, List.of("eng"))).outcome())
                .isEqualTo(RowSecurityOutcome.APPLIED);
    }

    @Test
    void classifyReportsDenyAllWhenADirectiveResolvedToNoValues() {
        var denying = directive("users", "team", RowSecurityOperator.IN, List.of());
        var other = directive("users", "level", RowSecurityOperator.EQUALS, List.of(3));
        var result = classify("SELECT * FROM users", denying, other);
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds())
                .containsExactlyInAnyOrder(denying.policyId(), other.policyId());
    }

    @Test
    void classifyTreatsUnaryIsNullAsAppliedNotDenyAll() {
        var result = classify("SELECT * FROM users",
                directive("users", "deleted_at", RowSecurityOperator.IS_NULL, List.of()));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
    }

    @Test
    void classifyReportsFailClosedForInsertAndUpsertIntoAPoliciedKeyspace() {
        var d = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        for (var sql : new String[]{
                "INSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})",
                "UPSERT INTO users (KEY, VALUE) VALUES ('k', {'a': 1})"}) {
            var result = classify(sql, d);
            assertThat(result.outcome()).as(sql).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
            assertThat(result.reason()).as(sql)
                    .contains("error.row_security_couchbase_insert_unsupported");
            assertThat(result.appliedPolicyIds()).as(sql).isEmpty();
        }
    }

    @Test
    void classifyReportsFailClosedForInsertSelectReadingAPoliciedKeyspace() {
        var result = classify("INSERT INTO archive (KEY META(u).id, VALUE u) SELECT u FROM users u",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_couchbase_unrewritable");
    }

    @Test
    void classifyReportsFailClosedForAPoliciedMerge() {
        var result = classify("MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_couchbase_unrewritable");
    }

    @Test
    void classifyReportsFailClosedForEveryUnrewritableSelectShape() {
        var d = directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"));
        for (var sql : new String[]{
                "WITH x AS (SELECT t.* FROM users t) SELECT * FROM x",
                "SELECT * FROM orders WHERE uid IN (SELECT RAW id FROM users)",
                "SELECT * FROM orders o JOIN users u ON o.uid = META(u).id",
                "SELECT * FROM orders o UNNEST o.items i JOIN users u ON i.uid = META(u).id",
                "SELECT * FROM users USE KEYS ['k1']",
                "SELECT * FROM users UNION SELECT * FROM admins"}) {
            var result = classify(sql, d);
            assertThat(result.outcome()).as(sql).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
            assertThat(result.reason()).as(sql)
                    .contains("error.row_security_couchbase_unrewritable");
            assertThat(result.appliedPolicyIds()).as(sql).isEmpty();
        }
    }

    @Test
    void classifyReportsFailClosedWhenThePoliciedKeyspaceIsOnlyReachedFromAJoin() {
        // The rewrite target (orders) carries no directive, but the statement still reads a
        // policied keyspace — apply() rejects it, so the classification must not say "unaffected".
        var result = classify("SELECT * FROM orders o JOIN users u ON o.uid = META(u).id",
                directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
    }

    @Test
    void classifyNeverMutatesTheStatementItInspects() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE age > 21");
        var result = applier.classify("couchbase", statement,
                List.of(directive("users", "team", RowSecurityOperator.EQUALS, List.of("eng"))));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(statement.sql()).isEqualTo("SELECT * FROM users WHERE age > 21");
    }
}
