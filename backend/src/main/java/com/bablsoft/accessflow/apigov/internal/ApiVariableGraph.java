package com.bablsoft.accessflow.apigov.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Dependency ordering over a connector's dynamic variables (AF-613).
 *
 * <p>A variable's expression may reference other variables, so evaluation must follow a topological
 * order of the resulting DAG. Kahn's algorithm drives it, with the ready set kept in a priority
 * queue keyed by the caller's input index — the repository already returns rows in
 * {@code (sort_order, created_at, id)} order, a total order, so independent variables evaluate in a
 * stable, operator-controlled sequence rather than whatever order the hash iteration happened to
 * produce. That matters because evaluation order is observable: two {@code TIMESTAMP} variables
 * resolved in a different order can produce different values.
 *
 * <p>Cycles are a configuration error caught at save time, not a runtime failure. The resolver runs
 * the same sort defensively at execution time; if it ever fires there, the config was mutated
 * outside the admin service.
 */
final class ApiVariableGraph {

    private ApiVariableGraph() {
    }

    /** One variable as the graph sees it: a name and the expression whose references form its edges. */
    record Node(String name, String expression) {
    }

    /** A dependency cycle. Carries the participating names — never an expression or a value. */
    static final class CycleException extends RuntimeException {

        private final transient List<String> names;

        CycleException(List<String> names) {
            super("Cyclic variable references: " + String.join(", ", names));
            this.names = List.copyOf(names);
        }

        List<String> names() {
            return names;
        }
    }

    /** A strict {@code {{var.x}}} reference to a variable that does not exist on the connector. */
    static final class UnknownReferenceException extends RuntimeException {

        private final transient String from;
        private final transient String missing;

        UnknownReferenceException(String from, String missing) {
            super("Variable '" + from + "' references unknown variable '" + missing + "'");
            this.from = from;
            this.missing = missing;
        }

        String from() {
            return from;
        }

        String missing() {
            return missing;
        }
    }

    /**
     * Orders {@code nodes} so that every variable comes after the ones it references.
     *
     * @param nodes the connector's variables, already in {@code (sort_order, created_at, id)} order
     * @return the same nodes in evaluation order
     * @throws UnknownReferenceException on a strict reference to a name not in {@code nodes}
     * @throws CycleException            when the references do not form a DAG
     */
    static List<Node> evaluationOrder(List<Node> nodes) {
        var indexByName = new LinkedHashMap<String, Integer>();
        for (var i = 0; i < nodes.size(); i++) {
            indexByName.put(nodes.get(i).name(), i);
        }

        // dependencies[i] = indices this node must wait for; dependents[i] = indices waiting on it.
        var dependencies = new ArrayList<Set<Integer>>(nodes.size());
        var dependents = new ArrayList<List<Integer>>(nodes.size());
        for (var i = 0; i < nodes.size(); i++) {
            dependencies.add(new LinkedHashSet<>());
            dependents.add(new ArrayList<>());
        }

        for (var i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            for (var missing : ApiVariableTemplate.strictVariableReferences(node.expression())) {
                if (!indexByName.containsKey(missing)) {
                    throw new UnknownReferenceException(node.name(), missing);
                }
            }
            for (var referenced : ApiVariableTemplate.variableReferences(node.expression())) {
                var target = indexByName.get(referenced);
                // A bare reference to a non-existent name stays literal at render time, so it is not
                // an edge. A self-reference is a one-node cycle and is reported as such.
                if (target == null) {
                    continue;
                }
                if (dependencies.get(i).add(target)) {
                    dependents.get(target).add(i);
                }
            }
        }

        var remaining = new int[nodes.size()];
        var ready = new PriorityQueue<Integer>();
        for (var i = 0; i < nodes.size(); i++) {
            remaining[i] = dependencies.get(i).size();
            if (remaining[i] == 0) {
                ready.add(i);
            }
        }

        var ordered = new ArrayList<Node>(nodes.size());
        while (!ready.isEmpty()) {
            var i = ready.poll();
            ordered.add(nodes.get(i));
            for (var dependent : dependents.get(i)) {
                if (--remaining[dependent] == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() != nodes.size()) {
            var stuck = new ArrayList<String>();
            for (var i = 0; i < nodes.size(); i++) {
                if (remaining[i] > 0) {
                    stuck.add(nodes.get(i).name());
                }
            }
            throw new CycleException(stuck);
        }
        return ordered;
    }
}
