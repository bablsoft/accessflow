package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Applies per-user response-field masking to a JSON API response, recursively by dot-path — the same
 * approach the MongoDB / DynamoDB / Elasticsearch engines use. A directive {@code user.email} redacts
 * that nested leaf wherever it occurs, descending through arrays. Leaves are replaced with a FULL mask
 * via {@link ColumnMasker}. Non-JSON bodies are returned unchanged (nothing to walk).
 */
@Component
public class ApiResponseMasker {

    private static final Logger log = LoggerFactory.getLogger(ApiResponseMasker.class);

    private final ObjectMapper objectMapper;

    public ApiResponseMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String mask(String body, List<String> restrictedPaths) {
        if (body == null || body.isBlank() || restrictedPaths == null || restrictedPaths.isEmpty()) {
            return body;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            log.debug("Response is not JSON; skipping field masking");
            return body;
        }
        for (var path : restrictedPaths) {
            if (path != null && !path.isBlank()) {
                applyPath(root, path.split("\\."), 0);
            }
        }
        return objectMapper.writeValueAsString(root);
    }

    private void applyPath(JsonNode node, String[] segments, int index) {
        if (node == null || index >= segments.length) {
            return;
        }
        if (node instanceof ArrayNode array) {
            for (var element : array) {
                applyPath(element, segments, index);
            }
            return;
        }
        if (node instanceof ObjectNode object) {
            var key = segments[index];
            if (index == segments.length - 1) {
                var value = object.get(key);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    object.put(key, ColumnMasker.apply(MaskingStrategy.FULL, value.asString(), null));
                } else if (value != null) {
                    maskAllLeaves(value);
                }
            } else {
                applyPath(object.get(key), segments, index + 1);
            }
        }
    }

    private void maskAllLeaves(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (var name : object.propertyStream().map(java.util.Map.Entry::getKey).toList()) {
                var child = object.get(name);
                if (child != null && child.isValueNode() && !child.isNull()) {
                    object.put(name, ColumnMasker.FULL_MASK);
                } else {
                    maskAllLeaves(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (var element : array) {
                maskAllLeaves(element);
            }
        }
    }
}
