package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Jackson mix-in that adds {@code "type"}-discriminated (de)serialization to the pure
 * {@link SqlRuleCondition} tree, keeping Jackson out of {@code sqlreview.api} (#1009). The
 * discriminator names are the persisted wire contract — see docs/03-data-model.md — and match
 * {@code workflow.api.ConditionNode}'s where the concept is the same.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SqlRuleCondition.And.class, name = "and"),
        @JsonSubTypes.Type(value = SqlRuleCondition.Or.class, name = "or"),
        @JsonSubTypes.Type(value = SqlRuleCondition.Not.class, name = "not"),
        @JsonSubTypes.Type(value = SqlRuleCondition.QueryTypeIn.class, name = "query_type"),
        @JsonSubTypes.Type(value = SqlRuleCondition.ReferencedTableMatches.class, name = "referenced_table"),
        @JsonSubTypes.Type(value = SqlRuleCondition.ReferencedColumnMatches.class, name = "referenced_column"),
        @JsonSubTypes.Type(value = SqlRuleCondition.FunctionCalled.class, name = "function_called"),
        @JsonSubTypes.Type(value = SqlRuleCondition.HasWhereClause.class, name = "has_where"),
        @JsonSubTypes.Type(value = SqlRuleCondition.HasLimitClause.class, name = "has_limit"),
        @JsonSubTypes.Type(value = SqlRuleCondition.HasOrderBy.class, name = "has_order_by"),
        @JsonSubTypes.Type(value = SqlRuleCondition.WhereAlwaysTrue.class, name = "where_always_true"),
        @JsonSubTypes.Type(value = SqlRuleCondition.JoinWithoutCondition.class, name = "join_without_condition"),
        @JsonSubTypes.Type(value = SqlRuleCondition.LikeLeadingWildcard.class, name = "like_leading_wildcard"),
        @JsonSubTypes.Type(value = SqlRuleCondition.Transactional.class, name = "transactional"),
        @JsonSubTypes.Type(value = SqlRuleCondition.SqlMatches.class, name = "sql_matches")
})
interface SqlRuleConditionMixin {
}
