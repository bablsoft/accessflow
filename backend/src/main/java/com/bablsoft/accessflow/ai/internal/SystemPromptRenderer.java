package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SupportedLanguage;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
              "affects_row_estimate": <integer or null>,
              "optimizations": [
                {
                  "type": <"INDEX"|"REWRITE">,
                  "title": <string — short imperative summary, e.g. "Add index on orders(customer_id)">,
                  "rationale": <string — why it helps, referencing the query and schema>,
                  "sql": <string — one concrete, runnable statement in the {{db_type}} dialect>
                }
              ]
            }

            Columns marked *RESTRICTED* in the schema context are sensitive and the values returned for them are masked at the proxy layer. If the SQL references any *RESTRICTED* column (in SELECT, WHERE, JOIN, ORDER BY, INSERT, UPDATE, or DELETE), add an issue with category="RESTRICTED_COLUMN_ACCESS" and severity="LOW" summarizing which restricted columns are touched. Do NOT raise the overall risk_level above MEDIUM solely for this reason — this is informational, not a blocker.

            Optimization suggestions: when the query would benefit from an index or a rewrite, populate "optimizations". Every "sql" value MUST be a single statement in the SAME query language as the analyzed query for this {{db_type}} engine — NOT necessarily SQL. For type="INDEX", give the engine's native index-definition statement (SQL / SQL++ / CQL: CREATE INDEX …; Neo4j Cypher: CREATE INDEX FOR (n:Label) ON (n.prop); MongoDB: db.collection.createIndex({…}); DynamoDB: a Global Secondary Index definition; Elasticsearch: a mapping / field change) — for engines without secondary indexes (e.g. Redis) omit INDEX suggestions and prefer a REWRITE. For type="REWRITE", give a complete, runnable, more-efficient version of the query in that same language (e.g. replace SELECT * with the needed columns, add a sargable predicate, remove a redundant subquery; for Cassandra/CQL restrict to partition & clustering keys; for MongoDB add an indexable filter/projection; for Elasticsearch use filter context on keyword fields). Reference only objects present in the schema context; never invent names. Suggest at most 3, ordered by impact. If there is no worthwhile optimization, return "optimizations": [].

            Database type: {{db_type}}
            Schema context: {{schema_context}}
            Knowledge base context (authoritative organization-specific guidance retrieved for this query — prefer it over general assumptions when it applies):
            {{rag_context}}
            SQL to analyze:
            {{sql}}
            Respond in: {{language}}. Translate the free-form fields (summary, issues[].message, issues[].suggestion) into that language. Keep risk_level and issues[].category as their original English enum values.
            """;

    /** The {@code {{sql}}} token a custom template must contain. */
    static final String SQL_PLACEHOLDER = "{{sql}}";

    /** The natural-language token substituted into the SQL-generation template. */
    static final String USER_REQUEST_PLACEHOLDER = "{{user_request}}";

    /** The RAG knowledge-base token; substituted with retrieved context or a fallback. */
    static final String RAG_CONTEXT_PLACEHOLDER = "{{rag_context}}";

    private static final String NO_RAG_CONTEXT = "(no knowledge base context available)";

    /** The token naming the engine's target query language (e.g. SQL, Cypher, CQL). */
    static final String TARGET_LANGUAGE_PLACEHOLDER = "{{target_language}}";

    /** The token holding engine-specific generation guidance (read-only bias + banned shapes). */
    static final String TARGET_GUIDANCE_PLACEHOLDER = "{{target_guidance}}";

    /**
     * Built-in natural-language → query generation prompt (AF-335, AF-439). Engine-language aware:
     * {@link #TARGET_LANGUAGE_PLACEHOLDER} and {@link #TARGET_GUIDANCE_PLACEHOLDER} are substituted
     * per {@link DbType} so the model drafts the engine's native query string (SQL, Mongo shell/JSON,
     * Cypher, CQL, Elasticsearch Query DSL, redis-cli, …). The generated query is a draft the user
     * reviews and submits through the normal pipeline, so the model is steered toward safe,
     * schema-grounded, read-oriented statements. The JSON envelope key stays {@code "sql"} for wire
     * compatibility — its value is now one runnable statement in the target query language.
     */
    static final String DEFAULT_QUERY_GENERATION_TEMPLATE = """
            You are an expert query author. Translate the user's natural-language request into a single
            runnable statement in the target query language ({{target_language}}) for the database, and
            respond ONLY with a JSON object matching this exact schema. Do not include any text outside
            the JSON.

            Schema:
            {
              "sql": <string — one runnable statement in {{target_language}}, no trailing semicolon required>
            }

            Rules:
            - Produce exactly ONE statement. Prefer a read-only operation unless the request clearly
              asks to modify data.
            - Use ONLY the tables/collections/indices/keyspaces/labels and their fields present in the
              schema context below (for this engine those map to {{target_language}}'s domain objects).
              Never invent names.
            - Never reference any field marked *RESTRICTED* in the schema context — those are sensitive
              and masked at the proxy layer.
            {{target_guidance}}
            - If the request is ambiguous, choose the most reasonable interpretation and still return a
              single statement.

            Database type: {{db_type}}
            Target query language: {{target_language}}
            Schema context: {{schema_context}}
            Knowledge base context (authoritative organization-specific guidance retrieved for this request — prefer it over general assumptions when it applies):
            {{rag_context}}
            Respond in: {{language}} for any string values you may include; keep query keywords and identifiers as {{target_language}} requires.
            User request:
            {{user_request}}
            """;

    /**
     * Per-engine generation profile (AF-439): the display name woven into the prompt, the editor
     * {@code syntax} id returned to the frontend ({@code engineModes} ids), and the engine-specific
     * guidance bullet (read-only bias + the banned shapes that engine rejects). Engines not listed
     * (the relational dialects + {@code CUSTOM}) fall back to {@link #SQL_PROFILE}.
     */
    private record QueryLanguageProfile(String displayName, String syntaxId, String guidance) {
    }

    private static final QueryLanguageProfile SQL_PROFILE = new QueryLanguageProfile(
            "SQL", "sql",
            "- Use dialect-appropriate syntax for the target database (date/time functions, identifier"
                    + " quoting, LIMIT/TOP, etc.).");

    private static final Map<DbType, QueryLanguageProfile> PROFILES = Map.ofEntries(
            Map.entry(DbType.COUCHBASE, new QueryLanguageProfile(
                    "Couchbase SQL++ (N1QL)", "sqlpp",
                    "- Use Couchbase SQL++ (N1QL). Do not use CURL(), JavaScript UDFs, the system:"
                            + " catalogs, or multiple statements.")),
            Map.entry(DbType.DYNAMODB, new QueryLanguageProfile(
                    "DynamoDB PartiQL", "partiql",
                    "- Use Amazon DynamoDB PartiQL with positional ? parameters. Do not use EXECUTE"
                            + " TRANSACTION or batch / multi-statement input.")),
            Map.entry(DbType.MONGODB, new QueryLanguageProfile(
                    "the MongoDB query language", "shell",
                    "- Emit a MongoDB shell command such as db.collection.find({...}),"
                            + " db.collection.aggregate([...]), db.collection.insertOne({...}), or"
                            + " db.collection.insertMany([{...}, ...]) (the JSON command-document form"
                            + " is also accepted). For an insert whose target collection the request"
                            + " names but the schema context does not list, use the requested"
                            + " collection name verbatim — MongoDB creates it on first write. Never"
                            + " use $where, $function, mapReduce, or other server-side JavaScript.")),
            Map.entry(DbType.REDIS, new QueryLanguageProfile(
                    "the Redis command language (redis-cli)", "cli",
                    "- Emit redis-cli commands from the read-oriented allow-list (GET, MGET, HGETALL,"
                            + " SCAN, LRANGE, …). Never use server-side scripting (EVAL/EVALSHA/FUNCTION),"
                            + " FLUSHALL/FLUSHDB, blocking, pub/sub, or transaction / multi-command"
                            + " forms.")),
            Map.entry(DbType.CASSANDRA, cqlProfile()),
            Map.entry(DbType.SCYLLADB, cqlProfile()),
            Map.entry(DbType.ELASTICSEARCH, searchProfile()),
            Map.entry(DbType.OPENSEARCH, searchProfile()),
            Map.entry(DbType.NEO4J, new QueryLanguageProfile(
                    "Cypher", "cypher",
                    "- Use Cypher: MATCH … WHERE … RETURN with named $params for values. Never use LOAD"
                            + " CSV or CALL outside the read-only procedure allow-list (db.labels,"
                            + " db.schema.*, …).")));

    private static QueryLanguageProfile cqlProfile() {
        return new QueryLanguageProfile(
                "CQL", "cql",
                "- Use CQL. Filter only on partition and clustering key columns with =, IN, <, <=, >,"
                        + " >= — never rely on ALLOW FILTERING. Do not use BEGIN … BATCH or CREATE/DROP"
                        + " FUNCTION/AGGREGATE.");
    }

    private static QueryLanguageProfile searchProfile() {
        return new QueryLanguageProfile(
                "the Elasticsearch Query DSL", "query_dsl",
                "- Emit a JSON Query DSL envelope whose first key names the operation, e.g."
                        + " { \"search\": \"<index>\", \"query\": { … } }. Never use script,"
                        + " script_fields, runtime_mappings, Painless, or cluster / system-index"
                        + " targets.");
    }

    private static QueryLanguageProfile profileFor(DbType dbType) {
        return dbType == null ? SQL_PROFILE : PROFILES.getOrDefault(dbType, SQL_PROFILE);
    }

    String defaultTemplate() {
        return DEFAULT_TEMPLATE;
    }

    String render(String sql, DbType dbType, String schemaContext, String language) {
        return render(null, sql, dbType, schemaContext, null, language);
    }

    String render(String template, String sql, DbType dbType, String schemaContext, String ragContext,
                  String language) {
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
                .replace(RAG_CONTEXT_PLACEHOLDER, ragText(ragContext))
                .replace("{{language}}", displayName)
                .replace(SQL_PLACEHOLDER, sql);
    }

    /**
     * Render the SQL-generation prompt for {@code userRequest}. Mirrors {@link #render}; the
     * {@code {{user_request}}} token is substituted last so request text that happens to contain a
     * token string is never re-substituted.
     */
    String renderGeneration(String userRequest, DbType dbType, String schemaContext, String ragContext,
                            String language) {
        var schemaText = (schemaContext == null || schemaContext.isBlank())
                ? "(no schema introspection available)"
                : schemaContext;
        var displayName = SupportedLanguage.fromCode(language)
                .map(SupportedLanguage::displayName)
                .orElse(SupportedLanguage.EN.displayName());
        var profile = profileFor(dbType);
        // {{user_request}} is replaced last so request text that happens to contain another token
        // string is never re-substituted.
        return DEFAULT_QUERY_GENERATION_TEMPLATE
                .replace(TARGET_LANGUAGE_PLACEHOLDER, profile.displayName())
                .replace(TARGET_GUIDANCE_PLACEHOLDER, profile.guidance())
                .replace("{{db_type}}", dbType == null ? "" : dbType.name())
                .replace("{{schema_context}}", schemaText)
                .replace(RAG_CONTEXT_PLACEHOLDER, ragText(ragContext))
                .replace("{{language}}", displayName)
                .replace(USER_REQUEST_PLACEHOLDER, userRequest == null ? "" : userRequest);
    }

    /**
     * The editor {@code syntax} id (matching the frontend {@code engineModes} ids) for a draft
     * generated against {@code dbType}. Deterministic per engine, except MongoDB which has two
     * editor syntaxes — a draft starting with {@code {} is the JSON command form ({@code json}),
     * otherwise the shell form ({@code shell}).
     */
    String syntaxFor(DbType dbType, String generatedQuery) {
        var profile = profileFor(dbType);
        if (dbType == DbType.MONGODB && generatedQuery != null
                && generatedQuery.stripLeading().startsWith("{")) {
            return "json";
        }
        return profile.syntaxId();
    }

    private static String ragText(String ragContext) {
        return (ragContext == null || ragContext.isBlank()) ? NO_RAG_CONTEXT : ragContext;
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
