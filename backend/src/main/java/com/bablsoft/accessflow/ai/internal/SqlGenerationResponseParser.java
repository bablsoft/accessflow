package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * Parses the AI provider's text-to-SQL response into a {@link GeneratedSqlResult}. Expects a strict
 * JSON envelope {@code {"sql": "<statement>"}} (mirroring the analysis parser), tolerating ```sql /
 * ```json markdown fences that some models add. A malformed or empty response raises
 * {@link AiAnalysisParseException} (mapped to HTTP 422).
 */
@Component
class SqlGenerationResponseParser {

    private static final Pattern LEADING_FENCE =
            Pattern.compile("^\\s*```(?:json|sql)?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FENCE = Pattern.compile("\\s*```\\s*$");

    private final ObjectMapper objectMapper;

    SqlGenerationResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    GeneratedSqlResult parse(String rawText, AiProviderType provider, String model,
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
        var sqlNode = root.get("sql");
        if (sqlNode == null || sqlNode.isNull() || !sqlNode.isString()) {
            throw new AiAnalysisParseException("Field 'sql' must be a string");
        }
        var sql = sqlNode.stringValue().trim();
        if (sql.isBlank()) {
            throw new AiAnalysisParseException("Field 'sql' must be non-blank");
        }
        return new GeneratedSqlResult(sql, provider, model, promptTokens, completionTokens);
    }

    private static String stripFences(String s) {
        var stripped = LEADING_FENCE.matcher(s).replaceFirst("");
        stripped = TRAILING_FENCE.matcher(stripped).replaceFirst("");
        return stripped.trim();
    }
}
