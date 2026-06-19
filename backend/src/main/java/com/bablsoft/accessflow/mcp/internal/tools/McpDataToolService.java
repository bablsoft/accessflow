package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SqlParsingException;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpAuditEntry;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpColumnSamples;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpPage;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpSqlValidation;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.SampleDataService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Read-mostly MCP tools that mirror governed capabilities without bypassing any guard: parse-only SQL
 * validation, masking-aware sample-row reads, and self-scoped audit-log access. Like the other MCP
 * tool services, every method resolves the caller from {@link McpCurrentUser} and delegates to
 * existing {@code core.api} / {@code proxy.api} / {@code audit.api} services so all authorization and
 * governance stays in the service layer.
 */
@Service
@RequiredArgsConstructor
public class McpDataToolService {

    private static final Logger log = LoggerFactory.getLogger(McpDataToolService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_SAMPLE_LIMIT = 50;
    private static final int MIN_SAMPLE_LIMIT = 1;
    private static final int MAX_SAMPLE_LIMIT = 200;

    private final McpCurrentUser currentUser;
    private final DatasourceAdminService datasourceAdminService;
    private final QueryParser queryParser;
    private final SampleDataService sampleDataService;
    private final AuditLogService auditLogService;

    @Tool(name = "validate_sql",
            description = "Parse-validate a query against a datasource's engine dialect WITHOUT "
                    + "executing it or running AI analysis. Returns whether it parses, the detected "
                    + "query type, the tables it references, and any referenced tables absent from the "
                    + "schema you can see. Parse errors come back as data (valid=false with parseError), "
                    + "not as an error — use this to check a draft before submit_query.")
    public McpSqlValidation validateSql(
            @ToolParam(description = "Target datasource UUID (one returned by list_datasources).") UUID datasourceId,
            @ToolParam(description = "The SQL/query string to validate.") String sql) {
        var claims = currentUser.requireClaims();
        DatasourceView view = currentUser.isAdmin()
                ? datasourceAdminService.getForAdmin(datasourceId, claims.organizationId())
                : datasourceAdminService.getForUser(datasourceId, claims.organizationId(), claims.userId());
        SqlParseResult parsed;
        try {
            parsed = queryParser.parse(sql, view.dbType());
        } catch (SqlParsingException ex) {
            return McpSqlValidation.invalid(ex.getMessage());
        }
        // Schema-mismatch detection is best-effort: it introspects the live database, which a pure
        // parse check must not depend on. A connectivity failure degrades to schemaChecked=false.
        List<String> unknownTables = List.of();
        boolean schemaChecked = false;
        try {
            var schema = datasourceAdminService.introspectSchema(
                    datasourceId, claims.organizationId(), claims.userId(), currentUser.isAdmin());
            unknownTables = unknownTables(parsed.referencedTables(), schema);
            schemaChecked = true;
        } catch (DatasourceConnectionTestException ex) {
            log.debug("validate_sql skipped schema-mismatch check for datasource {}: {}",
                    datasourceId, ex.getMessage());
        }
        return McpSqlValidation.valid(parsed, unknownTables, schemaChecked);
    }

    @Tool(name = "get_column_samples",
            description = "Return a bounded sample of rows from one table/collection, with the SAME "
                    + "row-level security and column masking applied as a normal governed read — masked "
                    + "columns carry the masked value, never the raw one (the restricted flag marks "
                    + "them). Requires read access to the table.")
    public McpColumnSamples getColumnSamples(
            @ToolParam(description = "Datasource UUID.") UUID datasourceId,
            @ToolParam(required = false, description = "Schema name (optional; engines without schemas ignore it).") String schema,
            @ToolParam(description = "Table or collection name.") String table,
            @ToolParam(required = false, description = "Max rows to return (default 50, max 200).") Integer limit) {
        var claims = currentUser.requireClaims();
        var result = sampleDataService.sample(datasourceId, claims.organizationId(), claims.userId(),
                currentUser.isAdmin(), schema, table, sampleLimit(limit));
        return McpColumnSamples.from(result);
    }

    @Tool(name = "get_audit_log",
            description = "List the calling user's own audit-log entries (actions they performed), "
                    + "newest first, optionally filtered by action, resource type, or time window. "
                    + "Always scoped to the caller — it never returns another user's activity.")
    public McpPage<McpAuditEntry> getAuditLog(
            @ToolParam(required = false, description = "Filter by AuditAction (e.g. QUERY_SUBMITTED, QUERY_EXECUTED).") String action,
            @ToolParam(required = false, description = "Filter by resource type — name (QUERY_REQUEST) or db value (query_request).") String resourceType,
            @ToolParam(required = false, description = "Only entries at/after this ISO-8601 instant (e.g. 2026-01-01T00:00:00Z).") String from,
            @ToolParam(required = false, description = "Only entries strictly before this ISO-8601 instant.") String to,
            @ToolParam(required = false, description = "Zero-based page number (default 0).") Integer page,
            @ToolParam(required = false, description = "Page size (default 20, max 100).") Integer size) {
        var claims = currentUser.requireClaims();
        var filter = new AuditLogQuery(
                claims.userId(),
                parseEnum(AuditAction.class, action),
                parseResourceType(resourceType),
                null,
                parseInstant(from, "from"),
                parseInstant(to, "to"));
        var result = auditLogService.query(claims.organizationId(), filter, pageRequest(page, size));
        return McpPage.from(result, McpAuditEntry::from);
    }

    private static List<String> unknownTables(Set<String> referencedTables, DatabaseSchemaView schema) {
        if (referencedTables.isEmpty()) {
            return List.of();
        }
        var known = new HashSet<String>();
        for (var s : schema.schemas()) {
            var schemaName = s.name() == null ? null : s.name().toLowerCase(Locale.ROOT);
            for (var t : s.tables()) {
                var tableName = t.name().toLowerCase(Locale.ROOT);
                known.add(tableName);
                if (schemaName != null && !schemaName.isBlank()) {
                    known.add(schemaName + "." + tableName);
                }
            }
        }
        return referencedTables.stream()
                .filter(ref -> !known.contains(ref))
                .sorted()
                .toList();
    }

    private static int sampleLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SAMPLE_LIMIT;
        }
        return Math.max(MIN_SAMPLE_LIMIT, Math.min(limit, MAX_SAMPLE_LIMIT));
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }

    private static AuditResourceType parseResourceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        try {
            return AuditResourceType.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Not an enum constant name — try the snake_case db value form below.
        }
        for (var type : AuditResourceType.values()) {
            if (type.dbValue().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown resource type '" + value + "'; expected an "
                + "AuditResourceType name (e.g. QUERY_REQUEST) or db value (e.g. query_request)");
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    field + " must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z)");
        }
    }
}
