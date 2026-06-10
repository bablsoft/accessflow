package com.bablsoft.accessflow.engine.mongodb;

import org.bson.Document;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Relaxed JSON ⇄ BSON helpers for the MongoDB query parser. Parsing accepts the mongo-shell dialect
 * (single quotes, unquoted property names, comments, trailing commas) which is a superset of strict
 * JSON, so the JSON-command form parses through the same path. The output is {@link Document}
 * (objects) / {@link List} (arrays) / scalars — directly consumable by the MongoDB driver as
 * filters, updates, and documents. No JavaScript is ever evaluated; query operators that execute
 * server-side JS or write outside the audited path are rejected up front.
 */
final class MongoJson {

    /** Operators that execute arbitrary JS or exfiltrate writes outside the governed path. */
    private static final Set<String> FORBIDDEN_OPERATORS =
            Set.of("$where", "$function", "$accumulator", "$out", "$merge");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private MongoJson() {
    }

    /** Parse a single JSON/relaxed-JSON value into a BSON-friendly object tree. */
    static Object parseValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (RuntimeException ex) {
            throw new MongoParseException("error.mongo.invalid_json", raw.strip());
        }
        return toBson(node);
    }

    /** Parse a value that must be a JSON object, returning it as a {@link Document}. */
    static Document parseDocument(String raw) {
        var value = parseValue(raw);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Document doc)) {
            throw new MongoParseException("error.mongo.expected_object");
        }
        return doc;
    }

    /** Parse a value that must be a JSON array of objects (e.g. an aggregation pipeline). */
    static List<Document> parseDocumentArray(String raw) {
        var value = parseValue(raw);
        if (!(value instanceof List<?> list)) {
            throw new MongoParseException("error.mongo.expected_array");
        }
        var docs = new ArrayList<Document>(list.size());
        for (var element : list) {
            if (!(element instanceof Document doc)) {
                throw new MongoParseException("error.mongo.expected_object");
            }
            docs.add(doc);
        }
        return docs;
    }

    static Object toBson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            var doc = new Document();
            for (var entry : node.properties()) {
                doc.put(entry.getKey(), toBson(entry.getValue()));
            }
            return doc;
        }
        if (node.isArray()) {
            var list = new ArrayList<>(node.size());
            for (var element : node) {
                list.add(toBson(element));
            }
            return list;
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
            // Doubles / decimals — keep precision via BigDecimal where the value is non-integral.
            double d = node.doubleValue();
            if (node.isBigDecimal() || node.isFloat() || node.isDouble()) {
                return d;
            }
            return BigDecimal.valueOf(d);
        }
        return node.asString();
    }

    /**
     * Recursively reject any forbidden operator (server-side JS, {@code $out}/{@code $merge}) in a
     * parsed value tree. Walks {@link Document}s and {@link List}s.
     */
    static void assertNoForbiddenOperators(Object value) {
        if (value instanceof Document doc) {
            for (var entry : doc.entrySet()) {
                var key = entry.getKey();
                if (key != null && FORBIDDEN_OPERATORS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new MongoParseException("error.mongo.forbidden_operator", key);
                }
                assertNoForbiddenOperators(entry.getValue());
            }
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key
                        && FORBIDDEN_OPERATORS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new MongoParseException("error.mongo.forbidden_operator", key);
                }
                assertNoForbiddenOperators(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (var element : list) {
                assertNoForbiddenOperators(element);
            }
        }
    }

    /**
     * Split a shell argument list on top-level commas, respecting {@code {}} / {@code []} /
     * {@code ()} nesting and single/double-quoted strings (with backslash escapes). Returns an empty
     * list for blank input.
     */
    static List<String> splitArgs(String args) {
        var out = new ArrayList<String>();
        if (args == null || args.isBlank()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> quote = c;
                case '{', '[', '(' -> depth++;
                case '}', ']', ')' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        out.add(args.substring(start, i).strip());
                        start = i + 1;
                    }
                }
                default -> { /* accumulate */ }
            }
        }
        if (depth != 0 || quote != 0) {
            throw new MongoParseException("error.mongo.unbalanced");
        }
        var tail = args.substring(start).strip();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }
}
