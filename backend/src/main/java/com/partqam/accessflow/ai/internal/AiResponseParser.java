package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiIssue;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.RiskLevel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
class AiResponseParser {

    private static final Pattern LEADING_FENCE = Pattern.compile("^\\s*```(?:json)?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FENCE = Pattern.compile("\\s*```\\s*$");

    private final ObjectMapper objectMapper;

    AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiAnalysisResult parse(String rawText, AiProviderType provider, String model,
                           int promptTokens, int completionTokens) {
        if (rawText == null || rawText.isBlank()) {
            throw new AiAnalysisParseException("AI response text was empty");
        }
        var json = stripFences(rawText.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new AiAnalysisParseException("AI response was not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new AiAnalysisParseException("AI response root must be a JSON object");
        }

        int riskScore = requireInt(root, "risk_score");
        if (riskScore < 0 || riskScore > 100) {
            throw new AiAnalysisParseException("risk_score must be in [0, 100], got " + riskScore);
        }
        var riskLevel = parseEnum(root, "risk_level");
        var summary = requireText(root, "summary");
        var missingIndexes = requireBoolean(root, "missing_indexes_detected");
        Long affects = parseNullableLong(root, "affects_row_estimate");

        var issues = parseIssues(root.get("issues"));

        return new AiAnalysisResult(riskScore, riskLevel, summary, issues, missingIndexes,
                affects, provider, model, promptTokens, completionTokens);
    }

    String issuesAsJson(List<AiIssue> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (RuntimeException e) {
            throw new AiAnalysisParseException("Failed to serialize issues: " + e.getMessage(), e);
        }
    }

    private static String stripFences(String s) {
        var stripped = LEADING_FENCE.matcher(s).replaceFirst("");
        stripped = TRAILING_FENCE.matcher(stripped).replaceFirst("");
        return stripped.trim();
    }

    private static int requireInt(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull() || !v.isInt()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be an integer");
        }
        return v.intValue();
    }

    private static String requireText(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull() || !v.isString()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be a string");
        }
        var text = v.stringValue();
        if (text.isBlank()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be non-blank");
        }
        return text;
    }

    private static boolean requireBoolean(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull() || !v.isBoolean()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be a boolean");
        }
        return v.booleanValue();
    }

    private static Long parseNullableLong(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be an integer or null");
        }
        return v.longValue();
    }

    private static RiskLevel parseEnum(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull() || !v.isString()) {
            throw new AiAnalysisParseException("Field '" + field + "' must be a string");
        }
        try {
            return RiskLevel.valueOf(v.stringValue());
        } catch (IllegalArgumentException e) {
            throw new AiAnalysisParseException("Field '" + field + "' must be one of LOW|MEDIUM|HIGH|CRITICAL");
        }
    }

    private static List<AiIssue> parseIssues(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new AiAnalysisParseException("Field 'issues' must be an array");
        }
        var out = new ArrayList<AiIssue>();
        for (int i = 0; i < node.size(); i++) {
            var item = node.get(i);
            if (!item.isObject()) {
                throw new AiAnalysisParseException("issues[" + i + "] must be an object");
            }
            out.add(new AiIssue(
                    parseEnum(item, "severity"),
                    requireText(item, "category"),
                    requireText(item, "message"),
                    requireText(item, "suggestion")));
        }
        return out;
    }
}
