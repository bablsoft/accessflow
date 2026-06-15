package com.bablsoft.accessflow.engine.neo4j;

import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Neo4j driver {@link Value} (and its nested object graph) into JSON-friendly Java values
 * the host serializes uniformly. Scalars map to {@code String}/{@code Long}/{@code Double}/
 * {@code Boolean}; richer Bolt types (temporal, spatial, duration) are stringified; bytes become
 * {@code base64:…}. A {@link Node} flattens to a map carrying {@code _elementId} + {@code _labels}
 * plus its properties; a {@link Relationship} to a map carrying {@code _elementId} + {@code _type} +
 * endpoints plus its properties; a {@link Path} to the list of its nodes and relationships. These
 * {@code _labels} / {@code _type} markers are what {@link Neo4jResultMapper} reads to apply
 * label-aware masking.
 */
final class Neo4jValueConverter {

    private static final String BASE64_PREFIX = "base64:";

    private Neo4jValueConverter() {
    }

    static Object convert(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return convertObject(value.asObject());
    }

    @SuppressWarnings("unchecked")
    static Object convertObject(Object object) {
        return switch (object) {
            case null -> null;
            case Node node -> nodeMap(node);
            case Relationship relationship -> relationshipMap(relationship);
            case Path path -> pathList(path);
            case Map<?, ?> map -> convertMap((Map<String, Object>) map);
            case List<?> list -> convertList(list);
            case byte[] bytes -> BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes);
            case String s -> s;
            case Boolean b -> b;
            case Number n -> n;
            default -> String.valueOf(object);
        };
    }

    private static Map<String, Object> nodeMap(Node node) {
        var out = new LinkedHashMap<String, Object>();
        out.put("_elementId", node.elementId());
        out.put(Neo4jResultMapper.LABELS_KEY, copyOf(node.labels()));
        out.putAll(convertMap(node.asMap()));
        return out;
    }

    private static Map<String, Object> relationshipMap(Relationship relationship) {
        var out = new LinkedHashMap<String, Object>();
        out.put("_elementId", relationship.elementId());
        out.put(Neo4jResultMapper.TYPE_KEY, relationship.type());
        out.put("_startNodeElementId", relationship.startNodeElementId());
        out.put("_endNodeElementId", relationship.endNodeElementId());
        out.putAll(convertMap(relationship.asMap()));
        return out;
    }

    private static List<Object> pathList(Path path) {
        var out = new ArrayList<>();
        out.add(nodeMap(path.start()));
        for (var segment : path) {
            out.add(relationshipMap(segment.relationship()));
            out.add(nodeMap(segment.end()));
        }
        return out;
    }

    private static Map<String, Object> convertMap(Map<String, Object> map) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            out.put(entry.getKey(), convertObject(entry.getValue()));
        }
        return out;
    }

    private static List<Object> convertList(List<?> list) {
        var out = new ArrayList<>(list.size());
        for (var element : list) {
            out.add(convertObject(element));
        }
        return out;
    }

    private static List<Object> copyOf(Iterable<String> values) {
        var out = new ArrayList<>();
        for (var value : values) {
            out.add(value);
        }
        return out;
    }
}
