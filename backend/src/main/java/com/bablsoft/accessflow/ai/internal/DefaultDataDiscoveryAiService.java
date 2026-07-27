package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService;
import com.bablsoft.accessflow.core.api.DataClassification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Discovery AI classification pass (AF-623). Builds a plain-text prompt from the (already
 * redacted) column contexts, calls the org's first usable {@code ai_config} through the holder's
 * freeform lane, and leniently parses the demanded strict-JSON response — unknown columns,
 * unknown classifications, and malformed payloads are dropped, never thrown.
 *
 * <p>The holder is resolved through an {@link ObjectProvider} (lazy, by-name) rather than injected
 * by concrete type, matching {@code AnomalySummaryService}: integration tests that
 * {@code @MockitoBean AiAnalyzerStrategy} replace the holder bean with a bare interface mock,
 * which is not assignable to the concrete field type.
 */
@Service
@RequiredArgsConstructor
class DefaultDataDiscoveryAiService implements DataDiscoveryAiService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataDiscoveryAiService.class);
    private static final int MAX_RATIONALE_LENGTH = 500;

    private final ObjectProvider<AiAnalyzerStrategyHolder> strategyHolder;
    private final ObjectMapper objectMapper;

    @Override
    public List<DiscoveryColumnSuggestion> classifyColumns(UUID organizationId,
                                                           DiscoveryTableContext context) {
        if (organizationId == null || context == null || context.columns().isEmpty()) {
            return List.of();
        }
        var response = strategyHolder.getObject()
                .classifyDiscoveryColumns(organizationId, buildPrompt(context))
                .orElse(null);
        if (response == null) {
            return List.of();
        }
        return parse(response, context);
    }

    private static String buildPrompt(DiscoveryTableContext context) {
        var sb = new StringBuilder();
        sb.append("Table: ").append(context.tableName()).append('\n');
        sb.append("Columns:\n");
        for (var column : context.columns()) {
            sb.append("- ").append(column.name());
            if (column.type() != null && !column.type().isBlank()) {
                sb.append(" (").append(column.type()).append(')');
            }
            if (!column.redactedSamples().isEmpty()) {
                sb.append(" samples: ").append(String.join(", ", column.redactedSamples()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<DiscoveryColumnSuggestion> parse(String response, DiscoveryTableContext context) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFences(response));
        } catch (RuntimeException ex) {
            log.warn("Discovery AI response was not valid JSON, ignoring: {}", ex.getMessage());
            return List.of();
        }
        var columnsNode = root.path("columns");
        if (!columnsNode.isArray()) {
            log.warn("Discovery AI response missing 'columns' array, ignoring");
            return List.of();
        }
        var knownColumns = new HashSet<String>();
        for (var column : context.columns()) {
            knownColumns.add(column.name().toLowerCase(Locale.ROOT));
        }
        var suggestions = new ArrayList<DiscoveryColumnSuggestion>();
        for (var node : columnsNode) {
            var suggestion = toSuggestion(node, knownColumns);
            if (suggestion != null) {
                suggestions.add(suggestion);
            }
        }
        return List.copyOf(suggestions);
    }

    private DiscoveryColumnSuggestion toSuggestion(JsonNode node, HashSet<String> knownColumns) {
        var columnName = node.path("column").asString("");
        if (columnName.isBlank() || !knownColumns.contains(columnName.toLowerCase(Locale.ROOT))) {
            return null;
        }
        DataClassification classification;
        try {
            classification = DataClassification.valueOf(
                    node.path("classification").asString("").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        var confidenceNode = node.path("confidence");
        var confidence = confidenceNode.isNumber()
                ? Math.clamp(confidenceNode.asInt(), 0, 100) : 0;
        var rationale = node.path("rationale").asString("");
        if (rationale.length() > MAX_RATIONALE_LENGTH) {
            rationale = rationale.substring(0, MAX_RATIONALE_LENGTH);
        }
        return new DiscoveryColumnSuggestion(columnName, classification, confidence,
                rationale.isBlank() ? null : rationale);
    }

    /** Models sometimes wrap JSON in markdown fences despite instructions — tolerate it. */
    private static String stripCodeFences(String response) {
        var trimmed = response.strip();
        if (trimmed.startsWith("```")) {
            var firstNewline = trimmed.indexOf('\n');
            var lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }
}
