package com.bablsoft.accessflow.apigov.internal.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Infers a compact JSON-Schema-shaped description from an <em>example</em> payload. Postman
 * collections carry saved examples rather than declared schemas, so a shape derived here is a
 * best-effort approximation: an array's element type comes from its first element, and a field
 * absent from the example is absent from the shape.
 */
class JsonShapeInferrer {

    /** Guards against a pathological nesting depth in an attacker-influenced document. */
    private static final int MAX_DEPTH = 12;

    private final ObjectMapper objectMapper;

    JsonShapeInferrer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Infers the shape of a JSON document, or returns {@code null} when it is absent/unparseable. */
    String inferFromJson(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (RuntimeException ex) {
            // A non-JSON example (XML, plain text, a body still full of {{placeholders}}) simply
            // yields no inferred shape — it is never a reason to reject the collection.
            return null;
        }
        return objectMapper.writeValueAsString(shapeOf(root, 0));
    }

    /** Infers the shape of a flat key/value body (urlencoded, form-data) as string properties. */
    String inferFromFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        var properties = objectMapper.createObjectNode();
        fields.keySet().forEach(key -> properties.set(key, typeNode("string")));
        var shape = objectMapper.createObjectNode();
        shape.put("type", "object");
        shape.set("properties", properties);
        return objectMapper.writeValueAsString(shape);
    }

    private JsonNode shapeOf(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) {
            return typeNode("object");
        }
        if (node.isObject()) {
            var properties = objectMapper.createObjectNode();
            node.propertyStream().forEach(e -> properties.set(e.getKey(), shapeOf(e.getValue(), depth + 1)));
            var shape = objectMapper.createObjectNode();
            shape.put("type", "object");
            shape.set("properties", properties);
            return shape;
        }
        if (node.isArray()) {
            var shape = objectMapper.createObjectNode();
            shape.put("type", "array");
            // An empty array carries no element type; anything else is inferred from element 0.
            shape.set("items", node.isEmpty() ? objectMapper.createObjectNode() : shapeOf(node.get(0), depth + 1));
            return shape;
        }
        return typeNode(scalarType(node));
    }

    private static String scalarType(JsonNode node) {
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isNull()) {
            return "null";
        }
        return "string";
    }

    private ObjectNode typeNode(String type) {
        var node = objectMapper.createObjectNode();
        node.put("type", type);
        return node;
    }
}
