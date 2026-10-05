package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.config.SqlReviewProperties;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewCustomRuleRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionCodec;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionValidator;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Spring-free {@link SqlRuleSource}s for the tests of its consumers. */
final class SqlRuleSources {

    private SqlRuleSources() {
    }

    /** The built-in catalog and no custom rules. */
    static SqlRuleSource builtIns() {
        return withCustomRules(List.of());
    }

    /** The built-in catalog plus {@code rows} for every organization. */
    static SqlRuleSource withCustomRules(List<SqlReviewCustomRuleEntity> rows) {
        var repository = mock(SqlReviewCustomRuleRepository.class);
        lenient().when(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(any(UUID.class)))
                .thenReturn(rows);
        lenient().when(repository.existsByOrganizationIdAndRuleId(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> rows.stream()
                        .anyMatch(row -> row.getRuleId().equals(invocation.getArgument(1))));
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        return new SqlRuleSource(new SqlRuleCatalog(), repository, codec(messages),
                new SqlRuleConditionValidator(messages), Clock.systemUTC(), new SqlReviewProperties(null));
    }

    /** An enabled custom rule row with the given condition JSON. */
    static SqlReviewCustomRuleEntity row(String ruleId, SqlReviewSeverity severity, String conditionJson) {
        var row = new SqlReviewCustomRuleEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(UUID.randomUUID());
        row.setRuleId(ruleId);
        row.setName("Rule " + ruleId);
        row.setDescription("About " + ruleId);
        row.setMessage("Matched {statement_type} on {tables}");
        row.setCategory(SqlRuleCategory.STATEMENT_SAFETY);
        row.setDefaultSeverity(severity);
        row.setCondition(conditionJson);
        row.setEnabled(true);
        return row;
    }

    static SqlRuleConditionCodec codec(StaticMessageSource messages) {
        return new SqlRuleConditionCodec(
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(), messages);
    }
}
