package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.neo4j.driver.summary.Plan;

import java.util.ArrayList;

/**
 * Maps a Neo4j Cypher {@code EXPLAIN} plan (the {@link Plan} from the Bolt result summary) into the
 * engine-neutral {@link QueryPlanNode} tree (issue AF-445). {@code EXPLAIN} plans the statement
 * without executing it, so no nodes/relationships are mutated. {@code EstimatedRows} (when present in
 * the operator arguments) becomes the node's estimated rows; the {@code Details} argument is the
 * one-line detail.
 */
final class Neo4jPlanMapper {

    private Neo4jPlanMapper() {
    }

    static QueryPlanNode toPlan(Plan plan) {
        var children = new ArrayList<QueryPlanNode>();
        for (var child : plan.children()) {
            children.add(toPlan(child));
        }
        String target = plan.identifiers().isEmpty() ? null : String.join(", ", plan.identifiers());
        return new QueryPlanNode(plan.operatorType(), target, estimatedRows(plan), null,
                detail(plan), children);
    }

    static Double estimatedRows(Plan plan) {
        var value = plan.arguments().get("EstimatedRows");
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static String detail(Plan plan) {
        var value = plan.arguments().get("Details");
        return value != null && !value.isNull() ? value.asString() : null;
    }
}
