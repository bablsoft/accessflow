package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL Server dry-run via {@code SET SHOWPLAN_ALL ON} — every subsequent statement returns its
 * <em>estimated</em> plan rows instead of being executed, so an {@code UPDATE}/{@code DELETE} never
 * runs. The session flag is toggled off in a {@code finally}. The plan rows (keyed by
 * {@code NodeId}/{@code Parent}) become the {@link QueryPlanNode} tree; the root {@code EstimateRows}
 * is the estimate.
 */
@Component
class SqlServerDryRunPlanner implements DryRunPlanner {

    @Override
    public Set<DbType> supportedTypes() {
        return Set.of(DbType.MSSQL);
    }

    @Override
    public QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException {
        Connection connection = request.connection();
        connection.setReadOnly(request.readOnlyEligible());
        try (Statement toggle = connection.createStatement()) {
            toggle.execute("SET SHOWPLAN_ALL ON");
        }
        try {
            QueryPlanNode tree;
            try (PreparedStatement statement = connection.prepareStatement(request.sql())) {
                statement.setQueryTimeout(request.timeoutSeconds());
                request.bind(statement);
                try (ResultSet rs = statement.executeQuery()) {
                    tree = readPlan(rs);
                }
            }
            Long estimated = tree != null && tree.estimatedRows() != null
                    ? Math.round(tree.estimatedRows())
                    : null;
            return QueryDryRunResult.of(request.engineId(), request.queryType(), estimated, tree,
                    null, request.appliedRowSecurityPolicyIds(), request.elapsed());
        } finally {
            try (Statement toggle = connection.createStatement()) {
                toggle.execute("SET SHOWPLAN_ALL OFF");
            } catch (SQLException ignored) {
                // session is discarded back to the pool on close; best-effort reset
            }
        }
    }

    private QueryPlanNode readPlan(ResultSet rs) throws SQLException {
        var nodes = new LinkedHashMap<Integer, QueryPlanNode>();
        var childrenOf = new LinkedHashMap<Integer, List<Integer>>();
        Integer rootId = null;
        while (rs.next()) {
            int nodeId = rs.getInt("NodeId");
            int parent = rs.getInt("Parent");
            String stmtText = trim(rs.getString("StmtText"));
            String physicalOp = optional(rs, "PhysicalOp");
            String argument = optional(rs, "Argument");
            double estimateRows = rs.getDouble("EstimateRows");
            Double rows = rs.wasNull() ? null : estimateRows;
            double subtreeCost = rs.getDouble("TotalSubtreeCost");
            Double cost = rs.wasNull() ? null : subtreeCost;
            String operation = physicalOp != null ? physicalOp : stmtText;
            nodes.put(nodeId, new QueryPlanNode(operation, null, rows, cost, argument));
            if (parent == 0) {
                if (rootId == null) {
                    rootId = nodeId;
                }
            } else {
                childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(nodeId);
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

    private static String optional(ResultSet rs, String column) {
        try {
            return trim(rs.getString(column));
        } catch (SQLException ex) {
            return null;
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        var t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
