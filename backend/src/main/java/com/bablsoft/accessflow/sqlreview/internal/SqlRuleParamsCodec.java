package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one boundary for the {@code sql_review_rule_configs.params} JSONB shape (#862): a JSON
 * object of string arrays — {@code {"names": ["pg_sleep"]}}, {@code {"globs": ["payroll.*"]}}. A
 * scalar value is tolerated on read as a one-element list; anything else is a malformed ruleset.
 */
@Component
public class SqlRuleParamsCodec {

    private final ObjectMapper mapper;
    private final MessageSource messageSource;

    public SqlRuleParamsCodec(ObjectMapper mapper, MessageSource messageSource) {
        this.mapper = mapper;
        this.messageSource = messageSource;
    }

    /** {@code null} / blank / {@code null} JSON read as no params. */
    public Map<String, List<String>> decode(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException ex) {
            throw invalid(ex);
        }
        if (root == null || root.isNull()) {
            return Map.of();
        }
        if (!root.isObject()) {
            throw invalid(null);
        }
        var out = new LinkedHashMap<String, List<String>>();
        for (var entry : root.properties()) {
            out.put(entry.getKey(), values(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    /** Empty params encode as {@code null} so the column stays NULL for parameterless rules. */
    public String encode(Map<String, List<String>> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(params);
        } catch (JacksonException ex) {
            throw invalid(ex);
        }
    }

    private List<String> values(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isValueNode()) {
            return List.of(node.asString());
        }
        if (!node.isArray()) {
            throw invalid(null);
        }
        var values = new ArrayList<String>(node.size());
        for (JsonNode element : node) {
            if (!element.isValueNode()) {
                throw invalid(null);
            }
            values.add(element.asString());
        }
        return List.copyOf(values);
    }

    private IllegalSqlReviewRulesetException invalid(Throwable cause) {
        var message = messageSource.getMessage("error.sql_review_rule_params_invalid", null,
                LocaleContextHolder.getLocale());
        return cause == null ? new IllegalSqlReviewRulesetException(message)
                : new IllegalSqlReviewRulesetException(message, cause);
    }
}
