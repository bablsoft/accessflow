package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SqlRuleConditionCodecTest {

    private static final JsonMapper MAPPER =
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private final SqlRuleConditionCodec codec = new SqlRuleConditionCodec(MAPPER, messages());

    private static StaticMessageSource messages() {
        var messages = new StaticMessageSource();
        messages.addMessage("error.sql_review_rule_condition_required", Locale.getDefault(), "required");
        messages.addMessage("error.sql_review_rule_condition_invalid", Locale.getDefault(), "invalid");
        return messages;
    }

    private static final SqlRuleCondition EVERY_VARIANT = new SqlRuleCondition.And(List.of(
            new SqlRuleCondition.Or(List.of(
                    new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.UPDATE)),
                    new SqlRuleCondition.ReferencedTableMatches(List.of("billing.*")),
                    new SqlRuleCondition.ReferencedColumnMatches(List.of("*.ssn")),
                    new SqlRuleCondition.FunctionCalled(List.of("dblink")))),
            new SqlRuleCondition.Not(new SqlRuleCondition.HasWhereClause(true)),
            new SqlRuleCondition.HasLimitClause(false),
            new SqlRuleCondition.HasOrderBy(true),
            new SqlRuleCondition.WhereAlwaysTrue(true),
            new SqlRuleCondition.JoinWithoutCondition(false),
            new SqlRuleCondition.LikeLeadingWildcard(true),
            new SqlRuleCondition.Transactional(false),
            new SqlRuleCondition.SqlMatches("(?i)drop\\s+table", true)));

    @Test
    void roundTripsEveryVariantThroughTheColumnString() {
        assertThat(codec.decode(codec.encode(EVERY_VARIANT))).isEqualTo(EVERY_VARIANT);
    }

    @Test
    void roundTripsEveryVariantThroughJsonNode() {
        assertThat(codec.fromJson(codec.toJson(EVERY_VARIANT))).isEqualTo(EVERY_VARIANT);
    }

    @Test
    void usesTheDocumentedWireNames() {
        var json = codec.encode(EVERY_VARIANT);

        for (String name : List.of("\"and\"", "\"or\"", "\"not\"", "\"query_type\"", "\"referenced_table\"",
                "\"referenced_column\"", "\"function_called\"", "\"has_where\"", "\"has_limit\"", "\"has_order_by\"",
                "\"where_always_true\"", "\"join_without_condition\"", "\"like_leading_wildcard\"",
                "\"transactional\"", "\"sql_matches\"", "\"any_of\"", "\"ignore_case\"")) {
            assertThat(json).contains(name);
        }
        assertThat(codec.decode("{\"type\":\"sql_matches\",\"pattern\":\"x\",\"ignore_case\":false}"))
                .isEqualTo(new SqlRuleCondition.SqlMatches("x", false));
    }

    @Test
    void rejectsMissingAndMalformedConditions() {
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.decode(null)).withMessage("required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.decode(" ")).withMessage("required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.decode("{\"type\":\"risk_level\"}")).withMessage("invalid");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.decode("{not json")).withMessage("invalid");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.fromJson(null)).withMessage("required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.fromJson(MAPPER.createObjectNode())).withMessage("required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> codec.fromJson(MAPPER.readTree("{\"type\":\"nope\"}"))).withMessage("invalid");
    }
}
