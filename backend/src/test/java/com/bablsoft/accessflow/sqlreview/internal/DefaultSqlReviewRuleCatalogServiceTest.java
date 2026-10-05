package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSqlReviewRuleCatalogServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private final StaticMessageSource messages = new StaticMessageSource();
    private final SqlRuleCatalog catalog = new SqlRuleCatalog();
    private final DefaultSqlReviewRuleCatalogService service =
            new DefaultSqlReviewRuleCatalogService(SqlRuleSources.builtIns(), messages);

    @Test
    void listsEveryRuleInCatalogOrderWithItsIdentity() {
        var views = service.rules(ORG, Locale.ENGLISH);

        assertThat(views).hasSize(18);
        assertThat(views).extracting(SqlReviewRuleView::ruleId)
                .containsExactlyElementsOf(catalog.rules().stream().map(rule -> rule.ruleId()).toList());
        var protectedTable = views.stream().filter(v -> v.ruleId().equals("protected_table")).findFirst().orElseThrow();
        assertThat(protectedTable.category()).isEqualTo(SqlRuleCategory.DATA_PROTECTION);
        assertThat(protectedTable.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
    }

    @Test
    void appendsTheOrganizationsCustomRulesWithTheirOwnNameAndDescription() {
        var withCustom = new DefaultSqlReviewRuleCatalogService(SqlRuleSources.withCustomRules(List.of(
                SqlRuleSources.row("custom_no_dblink", SqlReviewSeverity.BLOCK,
                        "{\"type\":\"function_called\",\"names\":[\"dblink\"]}"))), messages);

        var views = withCustom.rules(ORG, Locale.GERMAN);

        assertThat(views).hasSize(19);
        assertThat(views.subList(0, 18)).noneMatch(SqlReviewRuleView::custom);
        assertThat(views.get(18)).satisfies(view -> {
            assertThat(view.ruleId()).isEqualTo("custom_no_dblink");
            assertThat(view.custom()).isTrue();
            assertThat(view.name()).isEqualTo("Rule custom_no_dblink");
            assertThat(view.description()).isEqualTo("About custom_no_dblink");
            assertThat(view.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
            assertThat(view.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(view.params()).isEmpty();
        });
    }

    @Test
    void localizesNameAndDescriptionPerLocaleAndFallsBackToTheKey() {
        messages.addMessage("sqlreview.rule.select_star.name", Locale.ENGLISH, "SELECT *");
        messages.addMessage("sqlreview.rule.select_star.description", Locale.ENGLISH, "No column list");
        messages.addMessage("sqlreview.rule.select_star.name", Locale.GERMAN, "SELECT * (de)");
        messages.addMessage("sqlreview.rule.select_star.description", Locale.GERMAN, "Keine Spaltenliste");

        var english = service.rules(ORG, Locale.ENGLISH).get(0);
        var german = service.rules(ORG, Locale.GERMAN).get(0);

        assertThat(english.name()).isEqualTo("SELECT *");
        assertThat(english.description()).isEqualTo("No column list");
        assertThat(german.name()).isEqualTo("SELECT * (de)");
        assertThat(german.description()).isEqualTo("Keine Spaltenliste");
        assertThat(service.rules(ORG, Locale.ENGLISH).get(1).name()).isEqualTo("sqlreview.rule.missing_where_on_update.name");
    }

    @Test
    void exposesTheParamSchemaOfTheParameterisedRules() {
        var byId = service.rules(ORG, Locale.ENGLISH).stream()
                .collect(java.util.stream.Collectors.toMap(SqlReviewRuleView::ruleId, v -> v));

        assertThat(byId.get("select_star").params()).isEmpty();
        assertThat(byId.get("disallowed_function").params()).singleElement().satisfies(param -> {
            assertThat(param.key()).isEqualTo("names");
            assertThat(param.required()).isTrue();
            assertThat(param.defaults()).containsExactly("pg_sleep", "sleep", "benchmark", "load_file");
            assertThat(param.valuePattern()).isNotBlank();
        });
        assertThat(byId.get("protected_table").params()).extracting(SqlReviewRuleParamView::key, SqlReviewRuleParamView::defaults)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("globs", List.of()));
    }
}
