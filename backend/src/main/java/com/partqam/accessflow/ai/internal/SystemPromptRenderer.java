package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.core.api.DatabaseSchemaView;
import com.partqam.accessflow.core.api.DbType;
import org.springframework.stereotype.Component;

@Component
class SystemPromptRenderer {

    private static final String TEMPLATE = """
            You are a database security and performance expert reviewing SQL before execution in production.
            Analyze the following SQL query and respond ONLY with a JSON object matching this exact schema.
            Do not include any text outside the JSON.

            Schema:
            {
              "risk_score": <integer 0-100>,
              "risk_level": <"LOW"|"MEDIUM"|"HIGH"|"CRITICAL">,
              "summary": <string — one sentence human-readable summary>,
              "issues": [
                {
                  "severity": <"LOW"|"MEDIUM"|"HIGH"|"CRITICAL">,
                  "category": <string — e.g. "MISSING_WHERE_CLAUSE", "SELECT_STAR", "MISSING_INDEX">,
                  "message": <string — clear explanation of the issue>,
                  "suggestion": <string — concrete fix>
                }
              ],
              "missing_indexes_detected": <boolean>,
              "affects_row_estimate": <integer or null>
            }

            Database type: %s
            Schema context: %s
            SQL to analyze:
            %s
            """;

    String render(String sql, DbType dbType, String schemaContext) {
        var schemaText = (schemaContext == null || schemaContext.isBlank())
                ? "(no schema introspection available)"
                : schemaContext;
        return TEMPLATE.formatted(dbType.name(), schemaText, sql);
    }

    String describeSchema(DatabaseSchemaView schema) {
        if (schema == null || schema.schemas() == null || schema.schemas().isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var s : schema.schemas()) {
            for (var t : s.tables()) {
                sb.append(s.name()).append('.').append(t.name()).append('(');
                boolean first = true;
                for (var c : t.columns()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(c.name()).append(' ').append(c.type());
                    if (c.primaryKey()) {
                        sb.append(" pk");
                    }
                    if (!c.nullable()) {
                        sb.append(" not null");
                    }
                }
                sb.append(")\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
