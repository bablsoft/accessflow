package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.web.model.CreateSqlReviewRulesetRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.EvaluateSqlReviewRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewEvaluationResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleConfigRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRulesetResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.UpdateSqlReviewRulesetRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewWebModelsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> List<String> violatedPaths(T bean) {
        return validator.validate(bean).stream().map(ConstraintViolation::getPropertyPath)
                .map(Object::toString).sorted().toList();
    }

    @Test
    void createRequestMapsToCommandAndDefaultsMissingRulesToEmpty() {
        var request = new CreateSqlReviewRulesetRequest("Prod", "d", DatasourceEnvironment.PRODUCTION, false,
                List.of(new SqlReviewRuleConfigRequest("protected_table", SqlReviewSeverity.BLOCK,
                        Map.of("globs", List.of("payroll.*")))));

        var command = request.toCommand();

        assertThat(command.name()).isEqualTo("Prod");
        assertThat(command.description()).isEqualTo("d");
        assertThat(command.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(command.enabled()).isFalse();
        assertThat(command.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("protected_table");
            assertThat(rule.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(rule.params()).containsEntry("globs", List.of("payroll.*"));
        });
        assertThat(new CreateSqlReviewRulesetRequest("x", null, null, null, null).toCommand().rules()).isEmpty();
    }

    @Test
    void ruleConfigRequestWithoutParamsReadsAsEmptyParams() {
        var view = new SqlReviewRuleConfigRequest("select_star", SqlReviewSeverity.OFF, null).toView();

        assertThat(view.params()).isEmpty();
    }

    @Test
    void updateRequestIsTotalEveryAbsentFieldIsAValue() {
        var command = new UpdateSqlReviewRulesetRequest("Renamed", null, null, null, null).toCommand();

        assertThat(command.name()).isEqualTo("Renamed");
        assertThat(command.description()).isEmpty();
        assertThat(command.environment()).isNull();
        assertThat(command.clearEnvironment()).isTrue();
        assertThat(command.enabled()).isTrue();
        assertThat(command.rules()).isEmpty();

        var bound = new UpdateSqlReviewRulesetRequest("R", "desc", DatasourceEnvironment.TEST, false,
                List.of(new SqlReviewRuleConfigRequest("select_star", SqlReviewSeverity.OFF, null))).toCommand();
        assertThat(bound.description()).isEqualTo("desc");
        assertThat(bound.environment()).isEqualTo(DatasourceEnvironment.TEST);
        assertThat(bound.clearEnvironment()).isFalse();
        assertThat(bound.enabled()).isFalse();
        assertThat(bound.rules()).extracting(SqlReviewRuleConfigView::ruleId).containsExactly("select_star");
    }

    @Test
    void createAndUpdateRequestsEnforceTheDocumentedConstraints() {
        var blank = new CreateSqlReviewRulesetRequest(" ", "x".repeat(2001), null, null,
                List.of(new SqlReviewRuleConfigRequest("", null, null)));
        assertThat(violatedPaths(blank)).containsExactly("description", "name", "rules[0].ruleId",
                "rules[0].severity");
        assertThat(violatedPaths(new CreateSqlReviewRulesetRequest("x".repeat(256), null, null, null,
                List.of(new SqlReviewRuleConfigRequest("r".repeat(101), SqlReviewSeverity.WARN, null)))))
                .containsExactly("name", "rules[0].ruleId");
        assertThat(violatedPaths(new UpdateSqlReviewRulesetRequest("", null, null, null, null)))
                .containsExactly("name");
        assertThat(violatedPaths(new CreateSqlReviewRulesetRequest("ok", null, null, null, null))).isEmpty();
    }

    @Test
    void evaluateRequestEnforcesTheAnalyzeConstraints() {
        assertThat(violatedPaths(new EvaluateSqlReviewRequest(null, " "))).containsExactly("datasourceId", "sql");
        assertThat(violatedPaths(new EvaluateSqlReviewRequest(UUID.randomUUID(), "x".repeat(100_001))))
                .containsExactly("sql");
        assertThat(violatedPaths(new EvaluateSqlReviewRequest(UUID.randomUUID(), "SELECT 1"))).isEmpty();
    }

    @Test
    void rulesetResponseMapsTheViewIncludingRuleParams() {
        var now = Instant.now();
        var id = UUID.randomUUID();
        var org = UUID.randomUUID();
        var view = new SqlReviewRulesetView(id, org, "Prod", null, DatasourceEnvironment.PRODUCTION, true,
                List.of(new SqlReviewRuleConfigView("protected_table", SqlReviewSeverity.BLOCK,
                        Map.of("globs", List.of("payroll.*")))), now, now);

        var response = SqlReviewRulesetResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.organizationId()).isEqualTo(org);
        assertThat(response.name()).isEqualTo("Prod");
        assertThat(response.description()).isNull();
        assertThat(response.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(response.enabled()).isTrue();
        assertThat(response.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("protected_table");
            assertThat(rule.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(rule.params()).containsEntry("globs", List.of("payroll.*"));
        });
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.updatedAt()).isEqualTo(now);
    }

    @Test
    void ruleResponseMapsTheCatalogView() {
        var response = SqlReviewRuleResponse.from(new SqlReviewRuleView("disallowed_function",
                SqlRuleCategory.STATEMENT_SAFETY, SqlReviewSeverity.BLOCK, "Banned function", "desc",
                List.of(new SqlReviewRuleParamView("names", true, List.of("pg_sleep"), "[a-z_]+"))));

        assertThat(response.ruleId()).isEqualTo("disallowed_function");
        assertThat(response.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(response.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(response.name()).isEqualTo("Banned function");
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.params()).singleElement().satisfies(param -> {
            assertThat(param.key()).isEqualTo("names");
            assertThat(param.required()).isTrue();
            assertThat(param.defaults()).containsExactly("pg_sleep");
            assertThat(param.valuePattern()).isEqualTo("[a-z_]+");
        });
    }

    @Test
    void evaluationResponseRendersEachFindingThroughTheSuppliedRenderer() {
        var first = new SqlReviewFinding("select_star", SqlReviewSeverity.WARN, 0, 2, Map.of());
        var second = new SqlReviewFinding("protected_table", SqlReviewSeverity.BLOCK, 1, null, Map.of("table", "t"));

        var response = SqlReviewEvaluationResponse.from(new SqlReviewResult(true, List.of(first, second)),
                finding -> "msg:" + finding.ruleId());

        assertThat(response.applicable()).isTrue();
        assertThat(response.findings()).hasSize(2);
        assertThat(response.findings().get(0).ruleId()).isEqualTo("select_star");
        assertThat(response.findings().get(0).lineNumber()).isEqualTo(2);
        assertThat(response.findings().get(0).message()).isEqualTo("msg:select_star");
        assertThat(response.findings().get(1).statementIndex()).isEqualTo(1);
        assertThat(response.findings().get(1).lineNumber()).isNull();
        assertThat(response.findings().get(1).severity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(SqlReviewEvaluationResponse.from(SqlReviewResult.notApplicable(), f -> "x").applicable()).isFalse();
    }
}
