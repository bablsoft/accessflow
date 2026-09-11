package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleCatalogService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.web.model.EvaluateSqlReviewRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SqlReviewControllerTest {

    private final SqlReviewService sqlReviewService = mock(SqlReviewService.class);
    private final SqlReviewRuleCatalogService catalogService = mock(SqlReviewRuleCatalogService.class);
    private final SqlReviewFindingRenderer renderer = mock(SqlReviewFindingRenderer.class);
    private final SqlReviewController controller =
            new SqlReviewController(sqlReviewService, catalogService, renderer);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private Authentication as(UserRoleType role) {
        return new UsernamePasswordAuthenticationToken(
                JwtClaims.forSystemRole(userId, "u@x.com", role, organizationId), "n/a", List.of());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void theCatalogIsGatedByTheSqlReviewManagePermissionAndEvaluateIsNot() throws NoSuchMethodException {
        var rules = SqlReviewController.class.getDeclaredMethod("rules").getAnnotation(PreAuthorize.class);
        var evaluate = SqlReviewController.class
                .getDeclaredMethod("evaluate", EvaluateSqlReviewRequest.class, Authentication.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(rules).isNotNull();
        assertThat(rules.value()).isEqualTo("hasAuthority('PERM_SQL_REVIEW_MANAGE')");
        assertThat(evaluate).isNull();
        assertThat(SqlReviewController.class.getAnnotation(PreAuthorize.class)).isNull();
    }

    @Test
    void rulesMapTheLocalizedCatalogForTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        when(catalogService.rules(Locale.GERMAN)).thenReturn(List.of(new SqlReviewRuleView("protected_table",
                SqlRuleCategory.DATA_PROTECTION, SqlReviewSeverity.BLOCK, "Geschützte Tabelle", "Beschreibung",
                List.of(new SqlReviewRuleParamView("globs", true, List.of(), "[a-z*.]+")))));

        var result = controller.rules();

        assertThat(result).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("protected_table");
            assertThat(rule.name()).isEqualTo("Geschützte Tabelle");
            assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(rule.params()).singleElement().satisfies(param -> {
                assertThat(param.key()).isEqualTo("globs");
                assertThat(param.required()).isTrue();
                assertThat(param.valuePattern()).isEqualTo("[a-z*.]+");
            });
        });
    }

    @Test
    void evaluateResolvesThroughUserVisibilityAndRendersMessagesInTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        var finding = new SqlReviewFinding("missing_where_on_delete", SqlReviewSeverity.BLOCK, 0, 1,
                Map.of("table", "t"));
        when(sqlReviewService.evaluateForUser(organizationId, userId, false, datasourceId, "DELETE FROM t"))
                .thenReturn(new SqlReviewResult(true, List.of(finding)));
        when(renderer.message(finding, Locale.FRENCH)).thenReturn("DELETE sans WHERE sur t");

        var result = controller.evaluate(new EvaluateSqlReviewRequest(datasourceId, "DELETE FROM t"),
                as(UserRoleType.ANALYST));

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("missing_where_on_delete");
            assertThat(f.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(f.statementIndex()).isZero();
            assertThat(f.lineNumber()).isEqualTo(1);
            assertThat(f.message()).isEqualTo("DELETE sans WHERE sur t");
        });
    }

    @Test
    void evaluatePassesTheQueryAdminBypassForAdmins() {
        when(sqlReviewService.evaluateForUser(eq(organizationId), eq(userId), eq(true), eq(datasourceId), any()))
                .thenReturn(SqlReviewResult.notApplicable());

        var result = controller.evaluate(new EvaluateSqlReviewRequest(datasourceId, "{}"), as(UserRoleType.ADMIN));

        assertThat(result.applicable()).isFalse();
        assertThat(result.findings()).isEmpty();
        verifyNoInteractions(renderer);
    }
}
