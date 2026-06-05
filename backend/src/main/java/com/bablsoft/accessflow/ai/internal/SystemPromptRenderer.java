package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SupportedLanguage;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class SystemPromptRenderer {

    /**
     * Built-in analyzer prompt. Admins may override it per {@code ai_config} row; the four
     * {@code {{...}}} tokens are substituted at render time. {@code {{sql}}} is mandatory in any
     * custom template (enforced by the service) — without it the model never sees the query.
     */
    static final String DEFAULT_TEMPLATE = """
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

            Columns marked *RESTRICTED* in the schema context are sensitive and the values returned for them are masked at the proxy layer. If the SQL references any *RESTRICTED* column (in SELECT, WHERE, JOIN, ORDER BY, INSERT, UPDATE, or DELETE), add an issue with category="RESTRICTED_COLUMN_ACCESS" and severity="LOW" summarizing which restricted columns are touched. Do NOT raise the overall risk_level above MEDIUM solely for this reason — this is informational, not a blocker.

            Database type: {{db_type}}
            Schema context: {{schema_context}}
            SQL to analyze:
            {{sql}}
            Respond in: {{language}}. Translate the free-form fields (summary, issues[].message, issues[].suggestion) into that language. Keep risk_level and issues[].category as their original English enum values.
            """;

    /** The {@code {{sql}}} token a custom template must contain. */
    static final String SQL_PLACEHOLDER = "{{sql}}";

    /** The natural-language token substituted into the SQL-generation template. */
    static final String USER_REQUEST_PLACEHOLDER = "{{user_request}}";

    /**
     * Built-in natural-language → SQL generation prompt. The generated SQL is a draft the user
     * reviews and submits through the normal pipeline, so the model is steered toward safe,
     * schema-grounded, read-oriented statements.
     */
    static final String DEFAULT_SQL_GENERATION_TEMPLATE = """
            You are an expert SQL author. Translate the user's natural-language request into a single
            valid SQL statement for the target database, and respond ONLY with a JSON object matching
            this exact schema. Do not include any text outside the JSON.

            Schema:
            {
              "sql": <string — one runnable SQL statement, no trailing semicolon required>
            }

            Rules:
            - Produce exactly ONE statement. Prefer a read-only SELECT unless the request clearly asks
              to modify data.
            - Use ONLY tables and columns present in the schema context below. Never invent names.
            - Never reference any column marked *RESTRICTED* in the schema context — those are
              sensitive and masked at the proxy layer.
            - Use dialect-appropriate syntax for the given database type (date/time functions,
              identifier quoting, LIMIT/TOP, etc.).
            - If the request is ambiguous, choose the most reasonable interpretation and still return
              a single statement.

            Database type: {{db_type}}
            Schema context: {{schema_context}}
            Respond in: {{language}} for any string values you may include; keep SQL keywords in English.
            User request:
            {{user_request}}
            """;

    String defaultTemplate() {
        return DEFAULT_TEMPLATE;
    }

    String render(String sql, DbType dbType, String schemaContext, String language) {
        return render(null, sql, dbType, schemaContext, language);
    }

    String render(String template, String sql, DbType dbType, String schemaContext, String language) {
        var effective = (template == null || template.isBlank()) ? DEFAULT_TEMPLATE : template;
        var schemaText = (schemaContext == null || schemaContext.isBlank())
                ? "(no schema introspection available)"
                : schemaContext;
        var displayName = SupportedLanguage.fromCode(language)
                .map(SupportedLanguage::displayName)
                .orElse(SupportedLanguage.EN.displayName());
        // {{sql}} is replaced last so SQL text that happens to contain another token string is
        // never re-substituted.
        return effective
                .replace("{{db_type}}", dbType.name())
                .replace("{{schema_context}}", schemaText)
                .replace("{{language}}", displayName)
                .replace(SQL_PLACEHOLDER, sql);
    }

    /**
     * Render the SQL-generation prompt for {@code userRequest}. Mirrors {@link #render}; the
     * {@code {{user_request}}} token is substituted last so request text that happens to contain a
     * token string is never re-substituted.
     */
    String renderGeneration(String userRequest, DbType dbType, String schemaContext, String language) {
        var schemaText = (schemaContext == null || schemaContext.isBlank())
                ? "(no schema introspection available)"
                : schemaContext;
        var displayName = SupportedLanguage.fromCode(language)
                .map(SupportedLanguage::displayName)
                .orElse(SupportedLanguage.EN.displayName());
        return DEFAULT_SQL_GENERATION_TEMPLATE
                .replace("{{db_type}}", dbType.name())
                .replace("{{schema_context}}", schemaText)
                .replace("{{language}}", displayName)
                .replace(USER_REQUEST_PLACEHOLDER, userRequest == null ? "" : userRequest);
    }

    String describeSchema(DatabaseSchemaView schema) {
        return describeSchema(schema, List.of());
    }

    String describeSchema(DatabaseSchemaView schema, List<String> restrictedColumns) {
        if (schema == null || schema.schemas() == null || schema.schemas().isEmpty()) {
            return null;
        }
        var restricted = parseRestricted(restrictedColumns);
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
                    if (isRestricted(restricted, s.name(), t.name(), c.name())) {
                        sb.append(" *RESTRICTED*");
                    }
                }
                sb.append(")\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static Restricted parseRestricted(List<String> entries) {
        var fq = new HashSet<String>();
        var tq = new HashSet<String>();
        var bare = new HashSet<String>();
        if (entries == null) {
            return new Restricted(fq, tq, bare);
        }
        for (var entry : entries) {
            if (entry == null) {
                continue;
            }
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var parts = trimmed.toLowerCase(Locale.ROOT).split("\\.");
            switch (parts.length) {
                case 1 -> bare.add(parts[0]);
                case 2 -> {
                    tq.add(parts[0] + "." + parts[1]);
                    bare.add(parts[1]);
                }
                default -> {
                    fq.add(parts[parts.length - 3] + "." + parts[parts.length - 2]
                            + "." + parts[parts.length - 1]);
                    tq.add(parts[parts.length - 2] + "." + parts[parts.length - 1]);
                    bare.add(parts[parts.length - 1]);
                }
            }
        }
        return new Restricted(fq, tq, bare);
    }

    private static boolean isRestricted(Restricted restricted, String schema, String table,
                                        String column) {
        if (column == null) {
            return false;
        }
        var c = column.toLowerCase(Locale.ROOT);
        if (schema != null && table != null
                && restricted.fullyQualified.contains(schema.toLowerCase(Locale.ROOT) + "."
                        + table.toLowerCase(Locale.ROOT) + "." + c)) {
            return true;
        }
        if (table != null
                && restricted.tableQualified.contains(table.toLowerCase(Locale.ROOT) + "." + c)) {
            return true;
        }
        return restricted.bare.contains(c);
    }

    private record Restricted(Set<String> fullyQualified, Set<String> tableQualified,
                              Set<String> bare) {
    }
}
