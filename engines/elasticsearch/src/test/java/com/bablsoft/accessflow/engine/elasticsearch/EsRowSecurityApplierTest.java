package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsRowSecurityApplierTest {

    private final EsQueryParser parser = new EsQueryParser(TestMessages.keyEcho());
    private final EsRowSecurityApplier applier = new EsRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective directive(RowSecurityOperator op, Object... values) {
        return new RowSecurityDirective(UUID.randomUUID(), "logs", "tenant", op, List.of(values));
    }

    private EsRowSecurityApplier.Applied applySearch(RowSecurityDirective... directives) {
        return applier.apply(parser.parseCommand("{\"search\":\"logs\",\"query\":{\"match_all\":{}}}"),
                List.of(directives));
    }

    @Test
    void wrapsUserQueryInBoolMustWithEqualsAsTermFilter() {
        var applied = applySearch(directive(RowSecurityOperator.EQUALS, "acme"));
        assertThat(EsJson.write(applied.command().query())).isEqualTo(
                "{\"bool\":{\"must\":[{\"match_all\":{}}],"
                        + "\"filter\":[{\"term\":{\"tenant\":\"acme\"}}]}}");
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void mapsEachOperatorToTheRightClause() {
        assertThat(filterJson(RowSecurityOperator.NOT_EQUALS, "x"))
                .isEqualTo("{\"bool\":{\"must_not\":{\"term\":{\"tenant\":\"x\"}}}}");
        assertThat(filterJson(RowSecurityOperator.LESS_THAN, 5))
                .isEqualTo("{\"range\":{\"tenant\":{\"lt\":5}}}");
        assertThat(filterJson(RowSecurityOperator.GREATER_THAN_OR_EQUAL, 5))
                .isEqualTo("{\"range\":{\"tenant\":{\"gte\":5}}}");
        assertThat(filterJson(RowSecurityOperator.IN, "a", "b"))
                .isEqualTo("{\"terms\":{\"tenant\":[\"a\",\"b\"]}}");
        assertThat(filterJson(RowSecurityOperator.NOT_IN, "a", "b"))
                .isEqualTo("{\"bool\":{\"must_not\":{\"terms\":{\"tenant\":[\"a\",\"b\"]}}}}");
    }

    @Test
    void emptyValuesAreFailClosedMatchNothing() {
        assertThat(filterJson(RowSecurityOperator.EQUALS))
                .isEqualTo("{\"bool\":{\"must_not\":{\"match_all\":{}}}}");
    }

    @Test
    void isNullMapsToMustNotExists() {
        assertThat(filterJson(RowSecurityOperator.IS_NULL))
                .isEqualTo("{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"tenant\"}}}}");
    }

    @Test
    void combinesMultipleDirectivesAsSeparateFilterClauses() {
        var applied = applySearch(
                directive(RowSecurityOperator.EQUALS, "acme"),
                new RowSecurityDirective(UUID.randomUUID(), "logs", "region",
                        RowSecurityOperator.EQUALS, List.of("eu")));
        assertThat(EsJson.write(applied.command().query())).contains(
                "\"filter\":[{\"term\":{\"tenant\":\"acme\"}},{\"term\":{\"region\":\"eu\"}}]");
        assertThat(applied.appliedPolicyIds()).hasSize(2);
    }

    @Test
    void leavesCommandUntouchedWhenNoDirectiveMatchesTheIndex() {
        var unrelated = new RowSecurityDirective(UUID.randomUUID(), "other", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var applied = applier.apply(parser.parseCommand("{\"search\":\"logs\"}"), List.of(unrelated));
        assertThat(applied.appliedPolicyIds()).isEmpty();
        assertThat(applied.command().query()).isNull();
    }

    @Test
    void rejectsWritesIntoAPoliciedIndexFailClosed() {
        var index = parser.parseCommand("{\"index\":\"logs\",\"document\":{\"a\":1}}");
        assertThatThrownBy(() -> applier.apply(index,
                List.of(directive(RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void leavesDdlUnaffected() {
        var ddl = parser.parseCommand("{\"delete_index\":\"logs\"}");
        var applied = applier.apply(ddl, List.of(directive(RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void matchesIndexOnLastDotSegmentCaseInsensitively() {
        assertThat(EsRowSecurityApplier.matchesIndex("es.Logs", "logs")).isTrue();
        assertThat(EsRowSecurityApplier.matchesIndex("logs", "other")).isFalse();
    }

    // ---- offline classification (AF-630) --------------------------------------------------------

    private EsCommand search() {
        return parser.parseCommand("{\"search\":\"logs\",\"query\":{\"match_all\":{}}}");
    }

    @Test
    void classifyReportsNotApplicableWhenNoDirectiveTargetsTheIndex() {
        var unrelated = new RowSecurityDirective(UUID.randomUUID(), "other", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = applier.classify("elasticsearch", search(), List.of(unrelated));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("elasticsearch");
        assertThat(result.appliedPolicyIds()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsNotApplicableForNoDirectivesAtAll() {
        assertThat(applier.classify("elasticsearch", search(), List.of()).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyReportsAppliedWithThePolicyIdsThatWouldTakeEffect() {
        var directive = directive(RowSecurityOperator.EQUALS, "acme");
        var result = applier.classify("elasticsearch", search(), List.of(directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsDenyAllWhenADirectiveResolvedToNoValues() {
        var directive = directive(RowSecurityOperator.IN);
        var result = applier.classify("elasticsearch", search(), List.of(directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyTreatsUnaryIsNullAsAppliedNotDenyAll() {
        assertThat(applier.classify("elasticsearch", search(),
                List.of(directive(RowSecurityOperator.IS_NULL))).outcome())
                .isEqualTo(RowSecurityOutcome.APPLIED);
    }

    @Test
    void classifyReportsFailClosedForAnIndexIntoAPoliciedIndex() {
        var command = parser.parseCommand("{\"index\":\"logs\",\"document\":{\"a\":1}}");
        var result = applier.classify("elasticsearch", command,
                List.of(directive(RowSecurityOperator.EQUALS, "acme")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_search_insert_unsupported");
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyReportsFailClosedForABulkIntoAPoliciedIndex() {
        var command = parser.parseCommand(
                "{\"bulk\":\"logs\",\"operations\":[{\"document\":{\"a\":1}}]}");
        var result = applier.classify("elasticsearch", command,
                List.of(directive(RowSecurityOperator.EQUALS, "acme")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("error.row_security_search_insert_unsupported");
    }

    @Test
    void classifyReportsAppliedWithNoPolicyIdsForUnaffectedDdl() {
        var result = applier.classify("elasticsearch", parser.parseCommand("{\"delete_index\":\"logs\"}"),
                List.of(directive(RowSecurityOperator.EQUALS, "acme")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyStampsTheEngineIdItIsGiven() {
        assertThat(applier.classify("opensearch", search(),
                List.of(directive(RowSecurityOperator.EQUALS, "acme"))).engineId())
                .isEqualTo("opensearch");
    }

    @Test
    void classifyNeverMutatesTheCommandItInspects() {
        var command = search();
        var before = EsJson.write(command.query());
        var result = applier.classify("elasticsearch", command,
                List.of(directive(RowSecurityOperator.EQUALS, "acme")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(EsJson.write(command.query())).isEqualTo(before);
    }

    private String filterJson(RowSecurityOperator op, Object... values) {
        var applied = applySearch(directive(op, values));
        return EsJson.write(applied.command().query().get("bool").get("filter").get(0));
    }
}
