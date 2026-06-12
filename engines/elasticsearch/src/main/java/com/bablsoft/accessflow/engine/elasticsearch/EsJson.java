package com.bablsoft.accessflow.engine.elasticsearch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strict-JSON helpers for the Elasticsearch / OpenSearch engine: parse the query envelope, scan it
 * for forbidden constructs (server-side scripting, cluster/node/snapshot APIs — the parity ban with
 * Mongo's {@code $where}), build the Query-DSL fragments the row-security applier splices, and
 * convert response nodes into JSON-serialisable Java values for the result mapper. The analogue of
 * the Mongo engine's {@code MongoJson}, but the tree stays as Jackson {@link JsonNode}s end-to-end
 * (the REST clients exchange raw JSON, so there is no driver document type to convert to).
 */
final class EsJson {

    /**
     * Field names that execute server-side scripts (any Painless entry point) or reach
     * cluster-level APIs — rejected anywhere in the envelope tree. {@code _source} is deliberately
     * NOT here: it is the legitimate field-selection option.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "script", "script_fields", "script_score", "scripted_metric", "runtime_mappings",
            "_cluster", "_nodes", "_snapshot");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private EsJson() {
    }

    /** Strict-JSON parse; a malformed envelope becomes a 422 at the parser boundary. */
    static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (RuntimeException ex) {
            throw new JsonException(raw == null ? "" : raw.strip());
        }
    }

    static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    static ObjectNode object() {
        return NF.objectNode();
    }

    static ArrayNode array() {
        return NF.arrayNode();
    }

    /** {@code {"match_all":{}}} — the default user query when none is supplied. */
    static ObjectNode matchAll() {
        var q = object();
        q.set("match_all", object());
        return q;
    }

    /**
     * {@code {"bool":{"must":[<userQuery>],"filter":[<rls clauses>]}}}. The user query is wrapped
     * (never merged into their {@code bool}) so the rewrite is provably non-widening, and the RLS
     * clauses go in {@code filter} context (conjunctive, unscored, cacheable).
     */
    static ObjectNode boolFilter(JsonNode userQuery, List<JsonNode> filters) {
        var must = array();
        must.add(userQuery);
        var filterArr = array();
        filters.forEach(filterArr::add);
        var bool = object();
        bool.set("must", must);
        bool.set("filter", filterArr);
        var q = object();
        q.set("bool", bool);
        return q;
    }

    static ObjectNode term(String field, Object value) {
        var inner = object();
        inner.set(field, valueNode(value));
        var q = object();
        q.set("term", inner);
        return q;
    }

    static ObjectNode range(String field, String op, Object value) {
        var bounds = object();
        bounds.set(op, valueNode(value));
        var inner = object();
        inner.set(field, bounds);
        var q = object();
        q.set("range", inner);
        return q;
    }

    static ObjectNode terms(String field, List<Object> values) {
        var arr = array();
        for (var value : values) {
            arr.add(valueNode(value));
        }
        var inner = object();
        inner.set(field, arr);
        var q = object();
        q.set("terms", inner);
        return q;
    }

    /** {@code {"bool":{"must_not":<clause>}}} — negates a clause (NOT_EQUALS / NOT_IN). */
    static ObjectNode not(JsonNode clause) {
        var bool = object();
        bool.set("must_not", clause);
        var q = object();
        q.set("bool", bool);
        return q;
    }

    /** {@code {"bool":{"must_not":{"match_all":{}}}}} — matches nothing (fail-closed). */
    static ObjectNode notMatchAll() {
        return not(matchAll());
    }

    /** {@code {"ids":{"values":[...]}}} — the lowered form of a {@code get} / {@code mget}. */
    static ObjectNode idsQuery(List<String> ids) {
        var values = array();
        ids.forEach(values::add);
        var inner = object();
        inner.set("values", values);
        var q = object();
        q.set("ids", inner);
        return q;
    }

    static JsonNode valueNode(Object value) {
        return switch (value) {
            case null -> NF.nullNode();
            case String s -> NF.textNode(s);
            case Boolean b -> NF.booleanNode(b);
            case Integer i -> NF.numberNode(i);
            case Long l -> NF.numberNode(l);
            case Short s -> NF.numberNode(s);
            case Double d -> NF.numberNode(d);
            case Float f -> NF.numberNode(f);
            case BigDecimal bd -> NF.numberNode(bd);
            case BigInteger bi -> NF.numberNode(bi);
            case Number n -> NF.numberNode(n.doubleValue());
            default -> NF.textNode(String.valueOf(value));
        };
    }

    /** Recursively reject any forbidden field name anywhere in the parsed envelope. */
    static void assertNoForbiddenConstructs(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (var entry : node.properties()) {
                var name = entry.getKey();
                if (name != null && FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new EsParseException("error.elasticsearch.forbidden_construct", name);
                }
                assertNoForbiddenConstructs(entry.getValue());
            }
        } else if (node.isArray()) {
            for (var element : node) {
                assertNoForbiddenConstructs(element);
            }
        }
    }

    /** Convert a response node into a JSON-serialisable Java value (scalars / Map / List). */
    static Object toJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : node.properties()) {
                out.put(entry.getKey(), toJava(entry.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            var out = new ArrayList<>(node.size());
            for (var element : node) {
                out.add(toJava(element));
            }
            return out;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isInt() || node.isLong() || node.isShort()) {
            long v = node.longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return (int) v;
            }
            return v;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.asString();
    }

    /** String form of a node for masking; objects/arrays are masked by their JSON text. */
    static String scalarString(JsonNode node) {
        if (node.isObject() || node.isArray()) {
            return write(node);
        }
        return node.asString();
    }

    /** Best-effort Elasticsearch field-type label inferred from a value node (result column type). */
    static String esTypeName(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "nested";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isInt() || node.isLong() || node.isShort()) {
            return "long";
        }
        if (node.isNumber()) {
            return "double";
        }
        return "text";
    }

    /** Malformed-JSON marker caught by the parser and surfaced as a 422. */
    static final class JsonException extends RuntimeException {

        private final transient String snippet;

        JsonException(String snippet) {
            super("Invalid JSON");
            this.snippet = snippet;
        }

        String snippet() {
            return snippet;
        }
    }
}
