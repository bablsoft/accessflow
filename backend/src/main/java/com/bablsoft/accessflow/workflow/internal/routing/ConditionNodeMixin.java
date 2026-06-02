package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Jackson mix-in that adds polymorphic {@code "type"}-discriminated (de)serialization to the pure
 * {@link ConditionNode} tree, keeping all Jackson annotations out of the {@code workflow.api}
 * package (which must stay third-party-free). Registered on a dedicated mapper in
 * {@link RoutingConditionCodec}. The discriminator names are the persisted wire contract — see
 * docs/03-data-model.md.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConditionNode.And.class, name = "and"),
        @JsonSubTypes.Type(value = ConditionNode.Or.class, name = "or"),
        @JsonSubTypes.Type(value = ConditionNode.Not.class, name = "not"),
        @JsonSubTypes.Type(value = ConditionNode.QueryTypeIn.class, name = "query_type"),
        @JsonSubTypes.Type(value = ConditionNode.ReferencedTableMatches.class, name = "referenced_table"),
        @JsonSubTypes.Type(value = ConditionNode.RiskLevelIn.class, name = "risk_level"),
        @JsonSubTypes.Type(value = ConditionNode.RiskScore.class, name = "risk_score"),
        @JsonSubTypes.Type(value = ConditionNode.RequesterRoleIn.class, name = "requester_role"),
        @JsonSubTypes.Type(value = ConditionNode.RequesterInGroup.class, name = "requester_group"),
        @JsonSubTypes.Type(value = ConditionNode.TimeOfDay.class, name = "time_of_day"),
        @JsonSubTypes.Type(value = ConditionNode.DayOfWeekIn.class, name = "day_of_week"),
        @JsonSubTypes.Type(value = ConditionNode.HasWhereClause.class, name = "has_where"),
        @JsonSubTypes.Type(value = ConditionNode.HasLimitClause.class, name = "has_limit"),
        @JsonSubTypes.Type(value = ConditionNode.Transactional.class, name = "transactional")
})
interface ConditionNodeMixin {
}
