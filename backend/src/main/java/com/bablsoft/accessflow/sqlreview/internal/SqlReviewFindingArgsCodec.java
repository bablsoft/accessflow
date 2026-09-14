package com.bablsoft.accessflow.sqlreview.internal;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one boundary for the {@code query_sql_review_findings.args} JSONB shape (#864): a flat JSON
 * object of strings — the message arguments a rule captured, keyed by placeholder name. Empty args
 * encode as {@code null} so the column stays NULL for parameterless messages, and an unreadable blob
 * decodes as no args rather than failing a read that renders a reviewer's page.
 */
@Component
public class SqlReviewFindingArgsCodec {

    private final ObjectMapper mapper;

    public SqlReviewFindingArgsCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(args);
    }

    public Map<String, String> decode(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException ex) {
            return Map.of();
        }
        if (root == null || !root.isObject()) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, String>();
        for (var entry : root.properties()) {
            var value = entry.getValue();
            out.put(entry.getKey(), value == null || value.isNull() ? "" : value.asString());
        }
        return Map.copyOf(out);
    }
}
