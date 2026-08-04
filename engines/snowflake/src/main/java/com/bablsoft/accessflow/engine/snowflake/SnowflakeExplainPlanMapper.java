package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.QueryPlanNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps the tabular {@code EXPLAIN USING TABULAR <stmt>} result set onto the engine-neutral
 * {@link QueryPlanNode} tree (AF-634). The aggregate {@code GlobalStats} row supplies the
 * total {@code bytesAssigned} — Snowflake's native pre-flight scan estimate after partition
 * pruning; the remaining rows are operators linked child→parent by id. Deliberately defensive:
 * columns are read by label only when present, so output drift across Snowflake releases
 * degrades to a sparser plan (or a {@code null} bytes estimate) instead of an error.
 */
final class SnowflakeExplainPlanMapper {

    private static final Pattern INT = Pattern.compile("-?\\d+");

    /** The mapped plan: root node (nullable), GlobalStats bytes (nullable), raw text lines. */
    record Explained(QueryPlanNode plan, Long bytesAssigned, String rawPlan) {
    }

    private record Operator(Integer id, List<Integer> parents, String operation, String objects,
                            String detail) {
    }

    private SnowflakeExplainPlanMapper() {
    }

    static Explained map(ResultSet resultSet) throws SQLException {
        var labels = labels(resultSet);
        Long globalBytes = null;
        var operators = new ArrayList<Operator>();
        var raw = new StringBuilder();
        while (resultSet.next()) {
            var operation = string(resultSet, labels, "operation");
            var partitionsTotal = number(resultSet, labels, "partitionsTotal");
            var partitionsAssigned = number(resultSet, labels, "partitionsAssigned");
            var bytesAssigned = number(resultSet, labels, "bytesAssigned");
            var objects = string(resultSet, labels, "objects");
            var expressions = string(resultSet, labels, "expressions");
            appendRawLine(raw, operation, objects, expressions, partitionsAssigned,
                    partitionsTotal, bytesAssigned);
            if (operation != null && operation.equalsIgnoreCase("GlobalStats")) {
                globalBytes = bytesAssigned;
                continue;
            }
            operators.add(new Operator(intOrNull(number(resultSet, labels, "id")),
                    parents(resultSet, labels),
                    operation == null ? "?" : operation, objects,
                    detail(expressions, partitionsAssigned, partitionsTotal, bytesAssigned)));
        }
        return new Explained(toTree(operators), globalBytes,
                raw.isEmpty() ? null : raw.toString());
    }

    // ---- tree assembly --------------------------------------------------------------------------

    private static QueryPlanNode toTree(List<Operator> operators) {
        if (operators.isEmpty()) {
            return null;
        }
        var ids = new HashSet<Integer>();
        for (var operator : operators) {
            if (operator.id() != null) {
                ids.add(operator.id());
            }
        }
        Operator root = null;
        Map<Integer, List<Operator>> childrenByParent = new LinkedHashMap<>();
        var orphans = new ArrayList<Operator>();
        for (var operator : operators) {
            var parent = firstKnownParent(operator, ids);
            if (parent == null) {
                if (root == null) {
                    root = operator;
                } else {
                    orphans.add(operator);
                }
            } else {
                childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(operator);
            }
        }
        if (root == null) {
            // Cyclic parent references — fall back to the first operator (visited-set bounded).
            root = operators.getFirst();
        }
        // Operators with an unknown parent id attach under the root (they always remain visible
        // in the raw plan text regardless).
        if (!orphans.isEmpty() && root.id() != null) {
            childrenByParent.computeIfAbsent(root.id(), k -> new ArrayList<>()).addAll(orphans);
        }
        return node(root, childrenByParent, new HashSet<>());
    }

    private static Integer firstKnownParent(Operator operator, Set<Integer> ids) {
        for (var parent : operator.parents()) {
            if (!parent.equals(operator.id()) && ids.contains(parent)) {
                return parent;
            }
        }
        return null;
    }

    private static QueryPlanNode node(Operator operator,
                                      Map<Integer, List<Operator>> childrenByParent,
                                      Set<Integer> visited) {
        var children = new ArrayList<QueryPlanNode>();
        if (operator.id() != null && visited.add(operator.id())) {
            for (var child : childrenByParent.getOrDefault(operator.id(), List.of())) {
                children.add(node(child, childrenByParent, visited));
            }
        }
        return new QueryPlanNode(operator.operation(), operator.objects(), null, null,
                operator.detail(), children);
    }

    // ---- row reading ----------------------------------------------------------------------------

    private static Set<String> labels(ResultSet resultSet) throws SQLException {
        var metaData = resultSet.getMetaData();
        var labels = new HashSet<String>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            labels.add(metaData.getColumnLabel(i).toLowerCase(Locale.ROOT));
        }
        return labels;
    }

    private static String string(ResultSet resultSet, Set<String> labels, String label)
            throws SQLException {
        if (!labels.contains(label.toLowerCase(Locale.ROOT))) {
            return null;
        }
        var value = resultSet.getObject(label);
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static Long number(ResultSet resultSet, Set<String> labels, String label)
            throws SQLException {
        if (!labels.contains(label.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return switch (resultSet.getObject(label)) {
            case null -> null;
            case Number n -> n.longValue();
            case Object o -> parseLong(String.valueOf(o));
        };
    }

    private static Long parseLong(String text) {
        try {
            return Long.valueOf(text.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Out-of-int-range ids degrade to {@code null}/absent — never an exception. */
    private static Integer intOrNull(Long value) {
        return value == null || value != value.intValue() ? null : value.intValue();
    }

    /** Parent links: {@code parentOperators} ({@code "[0, 2]"}) with {@code parent} as fallback. */
    private static List<Integer> parents(ResultSet resultSet, Set<String> labels)
            throws SQLException {
        var raw = string(resultSet, labels, "parentOperators");
        if (raw == null) {
            var single = intOrNull(number(resultSet, labels, "parent"));
            return single == null ? List.of() : List.of(single);
        }
        var parents = new ArrayList<Integer>();
        var matcher = INT.matcher(raw);
        while (matcher.find()) {
            var parent = intOrNull(parseLong(matcher.group()));
            if (parent != null) {
                parents.add(parent);
            }
        }
        return parents;
    }

    // ---- formatting -----------------------------------------------------------------------------

    private static String detail(String expressions, Long partitionsAssigned, Long partitionsTotal,
                                 Long bytesAssigned) {
        var parts = new ArrayList<String>();
        if (expressions != null) {
            parts.add(expressions);
        }
        if (partitionsAssigned != null && partitionsTotal != null) {
            parts.add("partitions " + partitionsAssigned + "/" + partitionsTotal);
        }
        if (bytesAssigned != null) {
            parts.add("bytes " + bytesAssigned);
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static void appendRawLine(StringBuilder raw, String operation, String objects,
                                      String expressions, Long partitionsAssigned,
                                      Long partitionsTotal, Long bytesAssigned) {
        if (!raw.isEmpty()) {
            raw.append('\n');
        }
        raw.append(operation == null ? "?" : operation);
        if (objects != null) {
            raw.append(' ').append(objects);
        }
        if (expressions != null) {
            raw.append(' ').append(expressions);
        }
        if (partitionsAssigned != null && partitionsTotal != null) {
            raw.append(" partitions=").append(partitionsAssigned).append('/')
                    .append(partitionsTotal);
        }
        if (bytesAssigned != null) {
            raw.append(" bytes=").append(bytesAssigned);
        }
    }
}
