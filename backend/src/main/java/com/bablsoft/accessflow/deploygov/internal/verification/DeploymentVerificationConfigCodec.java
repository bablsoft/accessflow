package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Converts between the stored {@code deployment_environments.verification} /
 * {@code deployment_requests.verification_config} jsonb blob and the typed
 * {@link DeploymentVerificationConfig} (#1023). Each check carries a {@code kind} discriminator;
 * durations are ISO-8601 strings. A blob the codec cannot understand — an unknown kind, a
 * malformed duration, a missing required field — raises {@link VerificationConfigParseException}.
 * The stored camelCase key names are frozen: renaming one breaks every stored row.
 */
@Component
@RequiredArgsConstructor
public class DeploymentVerificationConfigCodec {

    private final ObjectMapper objectMapper;

    /** Raised when a stored verification config cannot be understood. */
    public static class VerificationConfigParseException extends RuntimeException {
        public VerificationConfigParseException(String message) {
            super(message);
        }
    }

    public DeploymentVerificationConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new VerificationConfigParseException(
                    "Verification config is not valid JSON: " + ex.getMessage());
        }
        if (root.isNull()) {
            return null;
        }
        if (!root.isObject()) {
            throw new VerificationConfigParseException("Verification config must be a JSON object");
        }
        var checksNode = root.get("checks");
        if (checksNode == null || !checksNode.isArray()) {
            throw new VerificationConfigParseException("checks must be an array");
        }
        var checks = new ArrayList<VerificationCheck>();
        for (var element : checksNode) {
            checks.add(check(element));
        }
        return new DeploymentVerificationConfig(
                duration(root, "window"),
                duration(root, "sampleInterval"),
                requiredInt(root, "failureThreshold"),
                checks);
    }

    public String toJson(DeploymentVerificationConfig config) {
        if (config == null) {
            return null;
        }
        var node = objectMapper.createObjectNode();
        putDuration(node, "window", config.window());
        putDuration(node, "sampleInterval", config.sampleInterval());
        node.put("failureThreshold", config.failureThreshold());
        var array = node.putArray("checks");
        for (var check : config.checks()) {
            var checkNode = array.addObject();
            checkNode.put("kind", check.kind().name());
            checkNode.put("name", check.name());
            switch (check) {
                case HttpHealthCheck http -> {
                    checkNode.put("url", http.url());
                    checkNode.put("expectedStatus", http.expectedStatus());
                    if (http.bodyContains() != null) {
                        checkNode.put("bodyContains", http.bodyContains());
                    }
                }
                case PromqlThresholdCheck promql -> {
                    checkNode.put("prometheusUrl", promql.prometheusUrl());
                    checkNode.put("query", promql.query());
                    checkNode.put("comparator", promql.comparator().name());
                    checkNode.put("threshold", promql.threshold());
                    checkNode.put("noDataIsFailure", promql.noDataIsFailure());
                }
            }
        }
        return objectMapper.writeValueAsString(node);
    }

    private static VerificationCheck check(JsonNode node) {
        if (!node.isObject()) {
            throw new VerificationConfigParseException("Each check must be a JSON object");
        }
        var kind = parseEnum(VerificationCheckKind.class, requiredText(node, "kind"), "kind");
        var name = requiredText(node, "name");
        return switch (kind) {
            case HTTP_HEALTH -> new HttpHealthCheck(name, requiredText(node, "url"),
                    requiredInt(node, "expectedStatus"), optionalText(node, "bodyContains"));
            case PROMQL_THRESHOLD -> new PromqlThresholdCheck(name,
                    requiredText(node, "prometheusUrl"),
                    requiredText(node, "query"),
                    parseEnum(VerificationComparator.class, requiredText(node, "comparator"),
                            "comparator"),
                    requiredNumber(node, "threshold"),
                    requiredBoolean(node, "noDataIsFailure"));
        };
    }

    private static void putDuration(ObjectNode node, String field, Duration value) {
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    private static Duration duration(JsonNode root, String field) {
        var raw = requiredText(root, field);
        try {
            return Duration.parse(raw);
        } catch (RuntimeException ex) {
            throw new VerificationConfigParseException(field + " must be an ISO-8601 duration");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new VerificationConfigParseException("Unknown " + field + ": " + raw);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        var value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new VerificationConfigParseException(field + " must be a non-blank string");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new VerificationConfigParseException(field + " must be a string");
        }
        return value.stringValue();
    }

    private static int requiredInt(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new VerificationConfigParseException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static double requiredNumber(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new VerificationConfigParseException(field + " must be a number");
        }
        return value.doubleValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new VerificationConfigParseException(field + " must be a boolean");
        }
        return value.booleanValue();
    }
}
