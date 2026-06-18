package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Oracle dry-run via {@code EXPLAIN PLAN SET STATEMENT_ID = '…' FOR <sql>}, which populates the
 * scratch {@code PLAN_TABLE} <em>without</em> executing the statement; the rows are then read back
 * and the planner's own rows deleted in a {@code finally}. The user's tables are never touched. The
 * root {@code CARDINALITY} is the estimate. A missing {@code PLAN_TABLE} surfaces as the translated
 * SQL error.
 */
@Component
class OracleDryRunPlanner implements DryRunPlanner {

    @Override
    public Set<DbType> supportedTypes() {
        return Set.of(DbType.ORACLE);
    }

    @Override
    public QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException {
        var connection = request.connection();
        String statementId = "af" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        try {
            try (PreparedStatement explain = connection.prepareStatement(
                    "EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + request.sql())) {
                explain.setQueryTimeout(request.timeoutSeconds());
                request.bind(explain);
                explain.execute();
            }
            QueryPlanNode tree = readPlan(connection, statementId);
            Long estimated = tree != null && tree.estimatedRows() != null
                    ? Math.round(tree.estimatedRows())
                    : null;
            return QueryDryRunResult.of(request.engineId(), request.queryType(), estimated, tree,
                    null, request.appliedRowSecurityPolicyIds(), request.elapsed());
        } finally {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM plan_table WHERE statement_id = ?")) {
                delete.setString(1, statementId);
                delete.executeUpdate();
            } catch (SQLException ignored) {
                // best-effort cleanup; the row ages out of PLAN_TABLE regardless
            }
        }
    }

    private QueryPlanNode readPlan(java.sql.Connection connection, String statementId)
            throws SQLException {
        var nodes = new LinkedHashMap<Integer, QueryPlanNode>();
        var childrenOf = new LinkedHashMap<Integer, List<Integer>>();
        Integer rootId = null;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, parent_id, operation, options, object_name, cardinality, cost "
                        + "FROM plan_table WHERE statement_id = ? ORDER BY id")) {
            select.setString(1, statementId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int parent = rs.getInt("parent_id");
                    boolean hasParent = !rs.wasNull();
                    String operation = rs.getString("operation");
                    String options = rs.getString("options");
                    String objectName = rs.getString("object_name");
                    double cardinality = rs.getDouble("cardinality");
                    Double rows = rs.wasNull() ? null : cardinality;
                    double cost = rs.getDouble("cost");
                    Double costValue = rs.wasNull() ? null : cost;
                    String op = options != null && !options.isBlank()
                            ? operation + " " + options
                            : operation;
                    nodes.put(id, new QueryPlanNode(op, objectName, rows, costValue, null));
                    if (hasParent) {
                        childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
                    } else {
                        rootId = id;
                    }
                }
            }
        }
        return rootId == null ? null : assemble(rootId, nodes, childrenOf);
    }

    private QueryPlanNode assemble(int id, Map<Integer, QueryPlanNode> nodes,
                                   Map<Integer, List<Integer>> childrenOf) {
        QueryPlanNode self = nodes.get(id);
        var childIds = childrenOf.get(id);
        if (childIds == null || childIds.isEmpty()) {
            return self;
        }
        var children = new ArrayList<QueryPlanNode>();
        for (int childId : childIds) {
            children.add(assemble(childId, nodes, childrenOf));
        }
        return new QueryPlanNode(self.operation(), self.target(), self.estimatedRows(),
                self.estimatedCost(), self.detail(), children);
    }
}
