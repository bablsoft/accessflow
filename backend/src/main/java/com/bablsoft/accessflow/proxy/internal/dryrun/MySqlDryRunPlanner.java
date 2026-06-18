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
 * MySQL / MariaDB dry-run via {@code EXPLAIN FORMAT=JSON <sql>} — plans without executing (only
 * {@code EXPLAIN ANALYZE} would run the statement, which we never emit). The {@code query_block}
 * JSON is walked best-effort into a {@link QueryPlanNode} tree; the estimate is the largest
 * per-table row figure. MariaDB's slightly different JSON shape is handled by reading the union of
 * the row-count keys; on any parse surprise the raw JSON is still returned.
 */
@Component
class MySqlDryRunPlanner implements DryRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(MySqlDryRunPlanner.class);

    private final ObjectMapper objectMapper;

    MySqlDryRunPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<DbType> supportedTypes() {
        return Set.of(DbType.MYSQL, DbType.MARIADB);
    }

    @Override
    public QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException {
        request.connection().setReadOnly(request.readOnlyEligible());
        String explainSql = "EXPLAIN FORMAT=JSON " + request.sql();
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
        Double maxRows = null;
        try {
            JsonNode block = objectMapper.readTree(json).get("query_block");
            if (block != null) {
                var tables = new ArrayList<QueryPlanNode>();
                collectTables(block, tables, 0);
                maxRows = tables.stream()
                        .map(QueryPlanNode::estimatedRows)
                        .filter(java.util.Objects::nonNull)
                        .max(Double::compareTo)
                        .orElse(null);
                tree = new QueryPlanNode("query_block", null, maxRows,
                        number(block.get("cost_info"), "query_cost"), null, tables);
            }
        } catch (RuntimeException ex) {
            log.debug("Could not parse MySQL EXPLAIN JSON, returning raw plan: {}", ex.getMessage());
        }
        Long estimated = maxRows != null ? Math.round(maxRows) : null;
        return result(request, estimated, tree, json);
    }

    /** Recursively gather every {@code table} access node anywhere under the query block. */
    private static void collectTables(JsonNode node, List<QueryPlanNode> out, int depth) {
        if (node == null || depth > 40) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectTables(element, out, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode table = node.get("table");
        if (table != null && table.isObject() && table.get("table_name") != null) {
            out.add(tableNode(table));
        }
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (value.isArray() || (value.isObject() && !"table".equals(entry.getKey()))) {
                collectTables(value, out, depth + 1);
            }
        }
    }

    private static QueryPlanNode tableNode(JsonNode table) {
        Double rows = firstNonNull(number(table, "rows_produced_per_join"),
                number(table, "rows_examined_per_scan"), number(table, "rows"));
        Double cost = number(table.get("cost_info"), "read_cost");
        return new QueryPlanNode(
                firstNonNull(text(table, "access_type"), "table"),
                text(table, "table_name"),
                rows,
                cost,
                text(table, "attached_condition"));
    }

    private static QueryDryRunResult result(DryRunPlanRequest request, Long estimated,
                                            QueryPlanNode tree, String json) {
        return QueryDryRunResult.of(request.engineId(), request.queryType(), estimated, tree, json,
                request.appliedRowSecurityPolicyIds(), request.elapsed());
    }
}
