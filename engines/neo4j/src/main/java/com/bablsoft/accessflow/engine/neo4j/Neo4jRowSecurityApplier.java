package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.engine.neo4j.CypherNodePattern.ClauseKind;
import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Kind;
import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Applies resolved row-security predicates to a parsed {@link CypherStatement}. Cypher has no
 * SQL-style {@code WHERE … FROM}: a row-security {@link RowSecurityDirective} on a node label is
 * translated into a property predicate ANDed onto the {@code WHERE} scoped to each {@code MATCH} /
 * {@code OPTIONAL MATCH} clause that binds a node variable of that label — its values bound as
 * Cypher <em>named parameters</em> ({@code $af_rls_n}), never string-concatenated. The same
 * MATCH-scoped splice governs reads, {@code SET} updates, and {@code DELETE}s, since all select the
 * affected nodes through a {@code MATCH}. It <em>fails closed</em> with
 * {@link UnrewritableRowSecurityException} (HTTP 422) on any shape that cannot be provably
 * filtered: a policied label that appears only without a bound variable (an anonymous
 * {@code (:Label)}), only in a {@code WHERE} predicate / pattern comprehension (no clause-level
 * MATCH binding), or under a scalar operator with no value. A statement that {@code CREATE}s or
 * {@code MERGE}s a policied label is rejected outright (a write cannot be filtered into existence),
 * mirroring the INSERT-into-policied rejection in every other engine. DDL is unaffected.
 */
class Neo4jRowSecurityApplier {

    static final String PARAM_PREFIX = "af_rls_";
    private static final Pattern SIMPLE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Depth-0 keywords that end a MATCH pattern region / WHERE expression. */
    private static final Set<String> CLAUSE_BOUNDARIES = Set.of(
            "MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "CREATE", "MERGE", "SET", "DELETE",
            "DETACH", "REMOVE", "UNWIND", "CALL", "FOREACH", "ORDER", "SKIP", "LIMIT", "UNION",
            "USE", "ON", "YIELD");

    record Applied(String cypher, Map<String, Object> parameters, Set<UUID> appliedPolicyIds) {
    }

    private final EngineMessages messages;

    Neo4jRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(CypherStatement statement, List<RowSecurityDirective> directives) {
        if (directives == null || directives.isEmpty() || statement.kind() == CypherStatementKind.DDL) {
            return new Applied(statement.cypher(), Map.of(), Set.of());
        }
        var parameters = new LinkedHashMap<String, Object>();
        var policyIds = new LinkedHashSet<UUID>();
        // clause anchor token index -> AND-joined predicate fragments to splice into that clause
        var fragmentsByClause = new LinkedHashMap<Integer, List<String>>();
        for (var directive : directives) {
            var label = lastSegment(directive.tableRef());
            if (label.isEmpty() || !statement.references().contains(label)) {
                continue; // policy does not target a label this statement touches
            }
            var matches = bindingsFor(statement, label);
            for (var pattern : matches) {
                var fragment = toFragment(pattern.variable(), directive, parameters, label);
                fragmentsByClause.computeIfAbsent(pattern.clauseKeyword(), k -> new ArrayList<>())
                        .add(fragment);
                policyIds.add(directive.policyId());
            }
        }
        if (fragmentsByClause.isEmpty()) {
            return new Applied(statement.cypher(), Map.of(), Set.of());
        }
        return new Applied(splice(statement, fragmentsByClause), Map.copyOf(parameters),
                Set.copyOf(policyIds));
    }

    // ---- directive matching -------------------------------------------------------------------

    /** The clause-level MATCH node patterns bound to {@code label}; fail-closed on unfilterable shapes. */
    private List<CypherNodePattern> bindingsFor(CypherStatement statement, String label) {
        var matches = new ArrayList<CypherNodePattern>();
        boolean anonymous = false;
        for (var pattern : statement.nodePatterns()) {
            if (!hasLabel(pattern, label)) {
                continue;
            }
            if (pattern.clauseKind() == ClauseKind.WRITE) {
                throw new UnrewritableRowSecurityException(
                        messages.get("error.row_security_neo4j_insert_unsupported", label));
            }
            if (pattern.hasVariable()) {
                matches.add(pattern);
            } else {
                anonymous = true;
            }
        }
        // The label is referenced (checked by the caller) but cannot be safely bound: it appears
        // only anonymously, or only outside a clause-level MATCH (a WHERE predicate / comprehension).
        if (anonymous || matches.isEmpty()) {
            throw unrewritable(label);
        }
        return matches;
    }

    private static boolean hasLabel(CypherNodePattern pattern, String label) {
        for (var candidate : pattern.labels()) {
            if (candidate.toLowerCase(Locale.ROOT).equals(label)) {
                return true;
            }
        }
        return false;
    }

    // ---- predicate building -------------------------------------------------------------------

    private String toFragment(String variable, RowSecurityDirective directive,
                              Map<String, Object> parameters, String label) {
        var property = escapeIdent(variable) + "." + escapeIdent(directive.columnName());
        var paramName = PARAM_PREFIX + parameters.size();
        var operator = directive.operator();
        if (operator == RowSecurityOperator.IN) {
            parameters.put(paramName, List.copyOf(directive.values()));
            return property + " IN $" + paramName;
        }
        if (operator == RowSecurityOperator.NOT_IN) {
            parameters.put(paramName, List.copyOf(directive.values()));
            return "NOT (" + property + " IN $" + paramName + ")";
        }
        if (directive.values().isEmpty()) {
            throw unrewritable(label); // a scalar operator needs a value
        }
        parameters.put(paramName, directive.values().get(0));
        var symbol = switch (operator) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "<>";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            default -> throw unrewritable(label);
        };
        return property + " " + symbol + " $" + paramName;
    }

    private static String escapeIdent(String name) {
        if (SIMPLE_IDENT.matcher(name).matches()) {
            return name;
        }
        return "`" + name.replace("`", "``") + "`";
    }

    // ---- WHERE splice -------------------------------------------------------------------------

    private static String splice(CypherStatement statement, Map<Integer, List<String>> fragmentsByClause) {
        var tokens = statement.tokens();
        var cypher = statement.cypher();
        // Collect (offset -> insertion) edits, then apply right-to-left so earlier offsets stay valid.
        var edits = new TreeMap<Integer, String>();
        for (var entry : fragmentsByClause.entrySet()) {
            var predicate = "(" + String.join(" AND ", entry.getValue()) + ")";
            int whereIndex = clauseWhere(tokens, entry.getKey());
            if (whereIndex >= 0) {
                int exprEnd = boundaryOffsetAfter(tokens, whereIndex + 1);
                addEdit(edits, tokens.get(whereIndex).end(), " (");
                addEdit(edits, exprEnd, ") AND " + predicate + " ");
            } else {
                int patternEnd = boundaryOffsetAfter(tokens, entry.getKey() + 1);
                addEdit(edits, patternEnd, " WHERE " + predicate + " ");
            }
        }
        var sb = new StringBuilder(cypher);
        for (var entry : edits.descendingMap().entrySet()) {
            sb.insert(entry.getKey(), entry.getValue());
        }
        return sb.toString();
    }

    private static void addEdit(Map<Integer, String> edits, int offset, String text) {
        edits.merge(offset, text, (a, b) -> a + b);
    }

    /** The depth-0 WHERE owned by the clause anchored at {@code anchor}, or -1 when it has none. */
    private static int clauseWhere(List<Token> tokens, int anchor) {
        for (int i = anchor + 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() != 0 || token.kind() != Kind.WORD
                    || !CLAUSE_BOUNDARIES.contains(token.value())) {
                continue;
            }
            return token.isWord("WHERE") ? i : -1;
        }
        return -1;
    }

    /** Start offset of the first depth-0 clause-boundary keyword at/after {@code from}, else end. */
    private static int boundaryOffsetAfter(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && CLAUSE_BOUNDARIES.contains(token.value())) {
                return token.start();
            }
        }
        return tokens.get(tokens.size() - 1).end();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String lastSegment(String ref) {
        if (ref == null) {
            return "";
        }
        var normalized = ref.toLowerCase(Locale.ROOT).strip();
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private UnrewritableRowSecurityException unrewritable(String label) {
        return new UnrewritableRowSecurityException(
                messages.get("error.row_security_neo4j_unrewritable", label));
    }
}
