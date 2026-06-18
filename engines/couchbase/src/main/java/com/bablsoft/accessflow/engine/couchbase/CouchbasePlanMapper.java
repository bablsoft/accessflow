package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryPlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps a Couchbase SQL++ {@code EXPLAIN} row into the engine-neutral {@link QueryPlanNode} tree
 * (issue AF-445). {@code EXPLAIN} returns the query plan without executing the statement. The single
 * row is {@code { "plan": { "#operator": …, "~children": [...] }, "text": "…" }}; we walk the
 * operator tree, keying each node off {@code #operator} and recursing into {@code ~child(ren)} and
 * any nested operator maps. Best-effort: returns {@code null} when no {@code plan} operator is found.
 */
final class CouchbasePlanMapper {

    private CouchbasePlanMapper() {
    }

    @SuppressWarnings("unchecked")
    static QueryPlanNode toPlan(Object row) {
        if (!(row instanceof Map<?, ?> map)) {
            return null;
        }
        Object plan = map.get("plan");
        if (plan instanceof Map<?, ?> planMap && planMap.containsKey("#operator")) {
            return mapOperator((Map<String, Object>) planMap, 0);
        }
        if (map.containsKey("#operator")) {
            return mapOperator((Map<String, Object>) map, 0);
        }
        return null;
    }

    static Long estimatedRows(Object row) {
        if (row instanceof Map<?, ?> map && map.get("plan") instanceof Map<?, ?> plan
                && plan.get("cardinality") instanceof Number cardinality) {
            return Math.round(cardinality.doubleValue());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static QueryPlanNode mapOperator(Map<String, Object> node, int depth) {
        var children = new ArrayList<QueryPlanNode>();
        if (depth < 40) {
            for (var entry : node.entrySet()) {
                if ("#operator".equals(entry.getKey())) {
                    continue;
                }
                collectChildren(entry.getValue(), children, depth);
            }
        }
        return new QueryPlanNode(
                asString(node.get("#operator")),
                asString(firstNonNull(node.get("keyspace"), node.get("index"), node.get("as"))),
                node.get("cardinality") instanceof Number n ? n.doubleValue() : null,
                node.get("cost") instanceof Number c ? c.doubleValue() : null,
                asString(node.get("condition")),
                children);
    }

    @SuppressWarnings("unchecked")
    private static void collectChildren(Object value, List<QueryPlanNode> out, int depth) {
        if (value instanceof Map<?, ?> child && child.containsKey("#operator")) {
            out.add(mapOperator((Map<String, Object>) child, depth + 1));
        } else if (value instanceof List<?> list) {
            for (var element : list) {
                if (element instanceof Map<?, ?> child && child.containsKey("#operator")) {
                    out.add(mapOperator((Map<String, Object>) child, depth + 1));
                }
            }
        }
    }

    private static Object firstNonNull(Object... values) {
        for (var value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
