package com.bablsoft.accessflow.engine.couchbase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers for the Couchbase result path. Query rows are fetched from the SDK as raw bytes
 * (so {@code SELECT RAW …} scalar/array rows work as well as object rows) and parsed here into
 * plain Java values ({@link Map} / {@link List} / scalars) consumable by the result mapper. The
 * Jackson used is the plugin's own, relocated when shaded, so it can never clash with the host's.
 */
final class CouchbaseJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CouchbaseJson() {
    }

    /** Parse one query-result row (raw JSON bytes) into a plain Java value tree. */
    static Object parseRow(byte[] raw) {
        return toJava(MAPPER.readTree(raw));
    }

    static Object toJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            for (var entry : node.properties()) {
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            var list = new ArrayList<>(node.size());
            for (var element : node) {
                list.add(toJava(element));
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
            return node.decimalValue();
        }
        return node.asString();
    }
}
