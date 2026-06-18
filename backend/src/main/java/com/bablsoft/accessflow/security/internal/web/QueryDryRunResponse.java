package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import com.bablsoft.accessflow.core.api.QueryType;

import java.util.List;

/**
 * Wire model for {@code POST /api/v1/queries/dry-run}. Field names serialize to snake_case via the
 * global Jackson naming strategy ({@code estimated_rows}, {@code raw_plan}, …).
 */
public record QueryDryRunResponse(
        boolean supported,
        String engineId,
        QueryType queryType,
        Long estimatedRows,
        PlanNode plan,
        String rawPlan,
        String unsupportedReason,
        long durationMs) {

    public record PlanNode(
            String operation,
            String target,
            Double estimatedRows,
            Double estimatedCost,
            String detail,
            List<PlanNode> children) {

        static PlanNode from(QueryPlanNode node) {
            return new PlanNode(node.operation(), node.target(), node.estimatedRows(),
                    node.estimatedCost(), node.detail(),
                    node.children().stream().map(PlanNode::from).toList());
        }
    }

    public static QueryDryRunResponse from(QueryDryRunResult result) {
        return new QueryDryRunResponse(
                result.supported(),
                result.engineId(),
                result.queryType(),
                result.estimatedRows(),
                result.plan() == null ? null : PlanNode.from(result.plan()),
                result.rawPlan(),
                result.unsupportedReason(),
                result.duration() == null ? 0L : result.duration().toMillis());
    }
}
