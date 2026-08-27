package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
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
 *
 * <p>SHOWPLAN is only reachable over a plain {@link Statement} (issue AF-762), so a dry-run whose
 * row-security rewrite produced positional binds cannot be planned at all and degrades to an
 * <em>unsupported</em> result rather than surfacing a driver error.
 */
@Component
class SqlServerDryRunPlanner implements DryRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(SqlServerDryRunPlanner.class);

    private final MessageSource messageSource;

    SqlServerDryRunPlanner(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public Set<DbType> supportedTypes() {
        return Set.of(DbType.MSSQL);
    }

    @Override
    public QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException {
        // Binds are non-empty exactly when RowSecurityRewriter spliced a policy predicate behind a
        // JdbcParameter. Those values can only be supplied through a PreparedStatement, which
        // SHOWPLAN cannot use (see below) — and inlining them into the SQL text would break the
        // proxy's no-string-concatenation rule. Fail closed and honest instead, before the session
        // flag is ever touched.
        if (!request.binds().isEmpty()) {
            return QueryDryRunResult.unsupported(request.engineId(),
                    msg("error.dry_run.mssql_row_security_unsupported"));
        }
        Connection connection = request.connection();
        connection.setReadOnly(request.readOnlyEligible());
        try (Statement toggle = connection.createStatement()) {
            toggle.execute("SET SHOWPLAN_ALL ON");
        }
        try {
            QueryPlanNode tree;
            // Deliberately a plain Statement, not a PreparedStatement — do not "fix" this back.
            // mssql-jdbc returns no plan whatsoever over the prepared/RPC path: executeQuery()
            // raises "The statement did not return a result set", and execute() + getMoreResults()
            // yields updateCount=-1 with zero rows. SHOWPLAN_XML behaves identically. A language
            // batch is the only shape that produces a plan (issue AF-762).
            //
            // This is not a SQL-injection surface. The statement text is the caller's own SQL,
            // parsed by JSqlParser upstream and therefore a single statement; the transactional
            // BEGIN; … COMMIT; envelope is refused by DefaultQueryExecutor.dryRun, so no stacked
            // batch reaches here; nothing is concatenated into the text; and binds is provably
            // empty above, so no value is ever interpolated. Nor is it an execution surface:
            // SHOWPLAN_ALL suppresses execution for every statement class the dry-run path can
            // classify, DDL and SET included — DefaultQueryExecutorMssqlIntegrationTest pins that
            // against a real SQL Server 2022 for UPDATE, DELETE, and CREATE/DROP TABLE.
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(request.timeoutSeconds());
                try (ResultSet rs = statement.executeQuery(request.sql())) {
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
            } catch (SQLException ex) {
                // Best-effort: HikariCP resets only autoCommit/readOnly/isolation/catalog/network
                // timeout on return, not arbitrary session state, so a failed reset would leave
                // SHOWPLAN ON for the next borrower of this connection. In practice this only
                // throws when the session is already dead, which the pool's own validation
                // discards — but it is worth an operator-visible warning either way.
                log.warn("Could not reset SET SHOWPLAN_ALL OFF on a SQL Server dry-run connection;"
                        + " the pool will discard it if the session is dead: {}", ex.getMessage());
            }
        }
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
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
