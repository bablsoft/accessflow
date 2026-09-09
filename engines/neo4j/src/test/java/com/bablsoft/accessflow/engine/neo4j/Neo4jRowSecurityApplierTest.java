package com.bablsoft.accessflow.engine.neo4j;

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

class Neo4jRowSecurityApplierTest {

    private final CypherQueryParser parser = new CypherQueryParser(TestMessages.keyEcho());
    private final Neo4jRowSecurityApplier applier = new Neo4jRowSecurityApplier(TestMessages.keyEcho());

    private static final UUID POLICY = UUID.randomUUID();

    private RowSecurityDirective directive(String label, String column, RowSecurityOperator op,
                                          Object... values) {
        return new RowSecurityDirective(POLICY, label, column, op, List.of(values));
    }

    private Neo4jRowSecurityApplier.Applied apply(String cypher, RowSecurityDirective... directives) {
        return applier.apply(parser.parseStatement(cypher), List.of(directives));
    }

    @Test
    void splicesWhereIntoMatchWithoutExistingWhere() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.cypher()).contains("WHERE (u.region = $af_rls_0)").contains("RETURN u");
        assertThat(applied.parameters()).containsEntry("af_rls_0", "EU");
        assertThat(applied.appliedPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void andsPredicateOntoExistingWhere() {
        var applied = apply("MATCH (u:User) WHERE u.active = true RETURN u",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.cypher())
                .contains("WHERE (")
                .contains("u.active = true")
                .contains("AND (u.region = $af_rls_0)");
    }

    @Test
    void usesNeverConcatenatedNamedParametersForEveryOperator() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("User", "tier", RowSecurityOperator.IN, "gold", "silver"));
        assertThat(applied.cypher()).contains("u.tier IN $af_rls_0");
        assertThat(applied.parameters()).containsEntry("af_rls_0", List.of("gold", "silver"));
    }

    @Test
    void rendersNotInAsNegatedMembership() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("User", "tier", RowSecurityOperator.NOT_IN, "banned"));
        assertThat(applied.cypher()).contains("NOT (u.tier IN $af_rls_0)");
    }

    @Test
    void combinesMultipleDirectivesForTheSameClauseWithAnd() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"),
                directive("User", "tier", RowSecurityOperator.GREATER_THAN, 2));
        assertThat(applied.cypher()).contains("u.region = $af_rls_0 AND u.tier > $af_rls_1");
        assertThat(applied.parameters()).containsEntry("af_rls_0", "EU").containsEntry("af_rls_1", 2);
    }

    @Test
    void filtersUpdatesAndDeletesThroughTheMatch() {
        assertThat(apply("MATCH (u:User) SET u.x = 1",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")).cypher())
                .contains("WHERE (u.region = $af_rls_0)").contains("SET u.x = 1");
        assertThat(apply("MATCH (u:User) DETACH DELETE u",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")).cypher())
                .contains("WHERE (u.region = $af_rls_0)").contains("DETACH DELETE u");
    }

    @Test
    void backtickQuotesNonSimplePropertyNames() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("User", "home region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.cypher()).contains("u.`home region` = $af_rls_0");
    }

    @Test
    void allowsEmptyInListAsDenyAllWithoutError() {
        var applied = apply("MATCH (u:User) RETURN u",
                new RowSecurityDirective(POLICY, "User", "tier", RowSecurityOperator.IN, List.of()));
        assertThat(applied.cypher()).contains("u.tier IN $af_rls_0");
        assertThat(applied.parameters()).containsEntry("af_rls_0", List.of());
    }

    @Test
    void failsClosedOnScalarOperatorWithNoValue() {
        assertThatThrownBy(() -> apply("MATCH (u:User) RETURN u",
                new RowSecurityDirective(POLICY, "User", "region", RowSecurityOperator.EQUALS, List.of())))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_neo4j_unrewritable");
    }

    @Test
    void failsClosedOnAnonymousPoliciedNode() {
        assertThatThrownBy(() -> apply("MATCH (:User) RETURN 1",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_neo4j_unrewritable");
    }

    @Test
    void failsClosedWhenLabelOnlyInWherePredicate() {
        // User appears only inside a pattern predicate, never as a clause-level MATCH binding.
        assertThatThrownBy(() -> apply("MATCH (n) WHERE (n)-->(:User) RETURN n",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void rejectsWriteCreatingAPoliciedLabel() {
        assertThatThrownBy(() -> apply("CREATE (u:User {id: 1})",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_neo4j_insert_unsupported");
        assertThatThrownBy(() -> apply("MERGE (u:User {id: 1})",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_neo4j_insert_unsupported");
    }

    @Test
    void leavesQueryUntouchedWhenNoDirectiveTargetsAReferencedLabel() {
        var applied = apply("MATCH (u:User) RETURN u",
                directive("Account", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.cypher()).isEqualTo("MATCH (u:User) RETURN u");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void leavesDdlUntouched() {
        var applied = apply("CREATE INDEX i FOR (u:User) ON (u.id)",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void splicesEachMatchClauseThatBindsThePoliciedLabel() {
        var applied = apply("MATCH (u:User) WITH u MATCH (v:User) RETURN u, v",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(applied.cypher()).contains("u.region = $af_rls_0").contains("v.region = $af_rls_1");
    }

    // ---- offline classification (AF-630) --------------------------------------------------------

    private RowSecurityClassification classify(String cypher, RowSecurityDirective... directives) {
        return applier.classify("neo4j", parser.parseStatement(cypher), List.of(directives));
    }

    @Test
    void classifyReportsNotApplicableWhenNoDirectiveTargetsAReferencedLabel() {
        var result = classify("MATCH (u:User) RETURN u",
                directive("Account", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("neo4j");
        assertThat(result.appliedPolicyIds()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsNotApplicableForNoDirectivesAtAll() {
        assertThat(classify("MATCH (u:User) RETURN u").outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyReportsNotApplicableForDdlWhichIsNeverFiltered() {
        assertThat(classify("CREATE INDEX i FOR (u:User) ON (u.id)",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU")).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyReportsAppliedWithThePolicyIdsThatWouldTakeEffect() {
        var result = classify("MATCH (u:User) WHERE u.active = true RETURN u",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(POLICY);
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsDenyAllWhenADirectiveResolvedToNoValues() {
        var result = classify("MATCH (u:User) RETURN u",
                new RowSecurityDirective(POLICY, "User", "tier", RowSecurityOperator.IN, List.of()));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void classifyReportsFailClosedForAScalarOperatorWithNoValue() {
        var result = classify("MATCH (u:User) RETURN u",
                new RowSecurityDirective(POLICY, "User", "region", RowSecurityOperator.EQUALS,
                        List.of()));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_neo4j_unrewritable");
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyReportsFailClosedForUnaryIsNullWhichCypherCannotSplice() {
        // Cypher has no unary splice: IS_NULL takes the value-less scalar path and is rejected, so
        // the honest classification is fail-closed rather than deny-all or applied.
        assertThat(classify("MATCH (u:User) RETURN u",
                new RowSecurityDirective(POLICY, "User", "deleted_at", RowSecurityOperator.IS_NULL,
                        List.of())).outcome())
                .isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
    }

    @Test
    void classifyReportsFailClosedForAnOperatorTheFragmentSwitchDoesNotHandle() {
        // The switch's default branch fails closed silently; classification must surface that.
        var result = classify("MATCH (u:User) RETURN u",
                directive("User", "deleted_at", RowSecurityOperator.IS_NULL, "ignored"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_neo4j_unrewritable");
    }

    @Test
    void classifyReportsFailClosedForAnAnonymousPoliciedNode() {
        var result = classify("MATCH (:User) RETURN 1",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_neo4j_unrewritable");
    }

    @Test
    void classifyReportsFailClosedWhenTheLabelOnlyAppearsInAWherePredicate() {
        var result = classify("MATCH (n) WHERE (n)-->(:User) RETURN n",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_neo4j_unrewritable");
    }

    @Test
    void classifyReportsFailClosedForAWriteCreatingAPoliciedLabel() {
        var created = classify("CREATE (u:User {id: 1})",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(created.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(created.reason()).contains("error.row_security_neo4j_insert_unsupported");
        var merged = classify("MERGE (u:User {id: 1})",
                directive("User", "region", RowSecurityOperator.EQUALS, "EU"));
        assertThat(merged.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(merged.reason()).contains("error.row_security_neo4j_insert_unsupported");
    }

    @Test
    void classifyNeverMutatesTheStatementItInspects() {
        var statement = parser.parseStatement("MATCH (u:User) RETURN u");
        var result = applier.classify("neo4j", statement,
                List.of(directive("User", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(statement.cypher()).isEqualTo("MATCH (u:User) RETURN u");
    }
}
