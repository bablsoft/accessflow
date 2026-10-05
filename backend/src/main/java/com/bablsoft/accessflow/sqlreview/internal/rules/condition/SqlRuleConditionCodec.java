package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Single boundary for the polymorphic {@code sql_review_custom_rules.condition} wire shape (#1009),
 * modelled on {@code workflow.internal.routing.RoutingConditionCodec}: a dedicated mapper rebuilt
 * from the injected one (inheriting SNAKE_CASE naming and non-null inclusion) with
 * {@link SqlRuleConditionMixin} registered, so the global mapper stays untouched.
 */
@Component
public class SqlRuleConditionCodec {

    private final ObjectMapper mapper;
    private final MessageSource messageSource;

    public SqlRuleConditionCodec(ObjectMapper objectMapper, MessageSource messageSource) {
        this.mapper = objectMapper.rebuild()
                .addMixIn(SqlRuleCondition.class, SqlRuleConditionMixin.class)
                .build();
        this.messageSource = messageSource;
    }

    /** Serialise a condition tree to the JSON string stored in the JSONB column. */
    public String encode(SqlRuleCondition condition) {
        try {
            return mapper.writeValueAsString(condition);
        } catch (RuntimeException ex) {
            throw new IllegalSqlReviewCustomRuleException(msg("error.sql_review_rule_condition_invalid"), ex);
        }
    }

    /** Deserialise the stored JSONB string back into a condition tree. */
    public SqlRuleCondition decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalSqlReviewCustomRuleException(msg("error.sql_review_rule_condition_required"));
        }
        try {
            return mapper.readValue(json, SqlRuleCondition.class);
        } catch (RuntimeException ex) {
            throw new IllegalSqlReviewCustomRuleException(msg("error.sql_review_rule_condition_invalid"), ex);
        }
    }

    /** Translate an inbound API condition tree into a pure {@link SqlRuleCondition}. */
    public SqlRuleCondition fromJson(JsonNode json) {
        if (json == null || json.isNull() || json.isEmpty()) {
            throw new IllegalSqlReviewCustomRuleException(msg("error.sql_review_rule_condition_required"));
        }
        try {
            return mapper.treeToValue(json, SqlRuleCondition.class);
        } catch (RuntimeException ex) {
            throw new IllegalSqlReviewCustomRuleException(msg("error.sql_review_rule_condition_invalid"), ex);
        }
    }

    /** Render a condition tree as a {@link JsonNode} for an API response. */
    public JsonNode toJson(SqlRuleCondition condition) {
        return mapper.valueToTree(condition);
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
