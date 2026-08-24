package com.bablsoft.accessflow.deploygov.internal.routing;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts between the stored {@code deployment_routing_policies.conditions} jsonb blob and the
 * typed {@link DeploymentRoutingConditions}. Reading is lenient about unknown keys (a policy
 * written by a newer version must not break an older one) but strict about the shape of the keys it
 * does understand — a malformed value raises {@link ConditionsParseException}, and the engine skips
 * that policy rather than treating the leaf as unconstrained.
 */
@Component
@RequiredArgsConstructor
public class DeploymentRoutingConditionCodec {

    private final ObjectMapper objectMapper;

    /** Raised when a stored conditions blob cannot be understood. */
    public static class ConditionsParseException extends RuntimeException {
        public ConditionsParseException(String message) {
            super(message);
        }
    }

    public DeploymentRoutingConditions fromJson(String json) {
        if (json == null || json.isBlank()) {
            return DeploymentRoutingConditions.NONE;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new ConditionsParseException("Conditions are not valid JSON: " + ex.getMessage());
        }
        if (root.isNull()) {
            return DeploymentRoutingConditions.NONE;
        }
        if (!root.isObject()) {
            throw new ConditionsParseException("Conditions must be a JSON object");
        }
        return new DeploymentRoutingConditions(
                strings(root, "environments"),
                strings(root, "providers"),
                riskLevel(root),
                strings(root, "versionGlobs"),
                days(root),
                time(root, "startTime"),
                time(root, "endTime"),
                text(root, "timezone"));
    }

    public String toJson(DeploymentRoutingConditions conditions) {
        var node = objectMapper.createObjectNode();
        if (conditions != null) {
            putStrings(node, "environments", conditions.environments());
            putStrings(node, "providers", conditions.providers());
            if (conditions.minRiskLevel() != null) {
                node.put("minRiskLevel", conditions.minRiskLevel().name());
            }
            putStrings(node, "versionGlobs", conditions.versionGlobs());
            if (!conditions.daysOfWeek().isEmpty()) {
                var array = node.putArray("daysOfWeek");
                conditions.daysOfWeek().stream().sorted().forEach(day -> array.add(day.intValue()));
            }
            putTime(node, "startTime", conditions.startTime());
            putTime(node, "endTime", conditions.endTime());
            if (conditions.timezone() != null && !conditions.timezone().isBlank()) {
                node.put("timezone", conditions.timezone());
            }
        }
        return objectMapper.writeValueAsString(node);
    }

    private static void putStrings(ObjectNode node, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        var array = node.putArray(field);
        values.forEach(array::add);
    }

    private static void putTime(ObjectNode node, String field, LocalTime value) {
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    private static List<String> strings(JsonNode root, String field) {
        var node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConditionsParseException(field + " must be an array of strings");
        }
        var values = new ArrayList<String>();
        for (var element : node) {
            if (!element.isString() || element.stringValue().isBlank()) {
                throw new ConditionsParseException(field + " must contain non-blank strings");
            }
            values.add(element.stringValue());
        }
        return List.copyOf(values);
    }

    private static Set<Integer> days(JsonNode root) {
        var node = root.get("daysOfWeek");
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new ConditionsParseException("daysOfWeek must be an array of ISO day numbers");
        }
        var values = new LinkedHashSet<Integer>();
        for (var element : node) {
            if (!element.isInt() || element.intValue() < 1 || element.intValue() > 7) {
                throw new ConditionsParseException(
                        "daysOfWeek must use ISO numbering 1 (Monday) to 7 (Sunday)");
            }
            values.add(element.intValue());
        }
        return Set.copyOf(values);
    }

    private static RiskLevel riskLevel(JsonNode root) {
        var raw = text(root, "minRiskLevel");
        if (raw == null) {
            return null;
        }
        try {
            return RiskLevel.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ConditionsParseException("Unknown minRiskLevel: " + raw);
        }
    }

    private static LocalTime time(JsonNode root, String field) {
        var raw = text(root, field);
        if (raw == null) {
            return null;
        }
        try {
            return LocalTime.parse(raw);
        } catch (RuntimeException ex) {
            throw new ConditionsParseException(field + " must be a wall-clock time (HH:mm[:ss])");
        }
    }

    private static String text(JsonNode root, String field) {
        var node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString() || node.stringValue().isBlank()) {
            throw new ConditionsParseException(field + " must be a non-blank string");
        }
        return node.stringValue();
    }
}
