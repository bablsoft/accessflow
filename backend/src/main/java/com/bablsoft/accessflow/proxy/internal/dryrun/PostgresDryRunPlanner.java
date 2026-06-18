package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.bablsoft.accessflow.proxy.internal.dryrun.JsonPlanSupport.firstNonNull;
import static com.bablsoft.accessflow.proxy.internal.dryrun.JsonPlanSupport.number;
import static com.bablsoft.accessflow.proxy.internal.dryrun.JsonPlanSupport.text;

/**
 * PostgreSQL dry-run via {@code EXPLAIN (FORMAT JSON) <sql>} — plans the statement without ever
 * executing it (no {@code ANALYZE}), so an {@code UPDATE}/{@code DELETE} is never run. The single
 * JSON row is parsed into a {@link QueryPlanNode} tree; the root {@code Plan Rows} is the estimate.
 */
@Component
class PostgresDryRunPlanner implements DryRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(PostgresDryRunPlanner.class);

    private final ObjectMapper objectMapper;

    PostgresDryRunPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<DbType> supportedTypes() {
        return Set.of(DbType.POSTGRESQL);
    }

    @Override
    public QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException {
        request.connection().setReadOnly(request.readOnlyEligible());
        String explainSql = "EXPLAIN (FORMAT JSON) " + request.sql();
        String json;
        try (PreparedStatement statement = request.connection().prepareStatement(explainSql)) {
            statement.setQueryTimeout(request.timeoutSeconds());
            request.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return result(request, null, null, null);
                }
                json = rs.getString(1);
            }
        }
        QueryPlanNode tree = null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode planNode = root.isArray() && !root.isEmpty()
                    ? root.get(0).get("Plan")
                    : root.get("Plan");
            if (planNode != null) {
                tree = toNode(planNode);
            }
        } catch (RuntimeException ex) {
            log.debug("Could not parse PostgreSQL EXPLAIN JSON, returning raw plan: {}",
                    ex.getMessage());
        }
        Long estimated = tree != null && tree.estimatedRows() != null
                ? Math.round(tree.estimatedRows())
                : null;
        return result(request, estimated, tree, json);
    }

    private static QueryPlanNode toNode(JsonNode node) {
        List<QueryPlanNode> children = new ArrayList<>();
        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                children.add(toNode(child));
            }
        }
        return new QueryPlanNode(
                text(node, "Node Type"),
                firstNonNull(text(node, "Relation Name"), text(node, "Alias")),
                number(node, "Plan Rows"),
                number(node, "Total Cost"),
                firstNonNull(text(node, "Index Cond"), text(node, "Filter"),
                        text(node, "Hash Cond"), text(node, "Join Filter"),
                        text(node, "Recheck Cond")),
                children);
    }

    private static QueryDryRunResult result(DryRunPlanRequest request, Long estimated,
                                            QueryPlanNode tree, String json) {
        return QueryDryRunResult.of(request.engineId(), request.queryType(), estimated, tree, json,
                request.appliedRowSecurityPolicyIds(), request.elapsed());
    }
}
