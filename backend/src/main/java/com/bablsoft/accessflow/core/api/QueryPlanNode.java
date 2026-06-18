package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * One node in an engine's execution plan tree (issue AF-445). Engine-neutral: relational EXPLAIN
 * nodes, a MongoDB winning-plan stage, a Couchbase SQL++ operator, and a Neo4j Cypher plan operator
 * all map onto this shape. {@code estimatedRows}/{@code estimatedCost} are best-effort and may be
 * {@code null} when the engine's plan does not expose them; {@code detail} carries an engine-defined
 * one-line summary (filter / index condition / extra info).
 */
public record QueryPlanNode(
        String operation,
        String target,
        Double estimatedRows,
        Double estimatedCost,
        String detail,
        List<QueryPlanNode> children) {

    public QueryPlanNode {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public QueryPlanNode(String operation, String target, Double estimatedRows,
                         Double estimatedCost, String detail) {
        this(operation, target, estimatedRows, estimatedCost, detail, List.of());
    }
}
