package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.TableNotFoundException;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.SampleDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultSampleDataService implements SampleDataService {

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final QueryExecutor queryExecutor;

    @Override
    public SelectExecutionResult sample(UUID datasourceId, UUID organizationId, UUID userId,
                                        boolean isAdmin, String schema, String table, int limit) {
        // 1. Authorization + introspection. introspectSchema enforces org + permission-row access
        //    (DatasourceNotFoundException -> 404) and surfaces connection failures (422). Its
        //    returned view is the allow-list we validate the requested target against.
        var schemaView = datasourceAdminService.introspectSchema(datasourceId, organizationId,
                userId, isAdmin);
        var target = resolveTarget(schemaView, schema, table)
                .orElseThrow(() -> new TableNotFoundException(datasourceId, table));

        var permission = permissionLookupService.findFor(userId, datasourceId);
        if (!isAdmin) {
            // Non-admins additionally need read capability + the target inside their allow-list.
            var view = permission.orElseThrow(() -> new TableNotFoundException(datasourceId, table));
            if (!view.canRead() || !targetAllowed(view, target)) {
                throw new TableNotFoundException(datasourceId, table);
            }
        }

        // 2. Resolve the caller's directives exactly as DefaultQueryLifecycleService.doExecute.
        var restrictedColumns = permission
                .map(DatasourceUserPermissionView::restrictedColumns)
                .orElse(List.of());
        var columnMasks = maskingPolicyResolutionService
                .resolveApplicable(organizationId, datasourceId, userId).stream()
                .map(m -> new ColumnMaskDirective(m.columnRef(), m.strategy(), m.params(),
                        m.policyId()))
                .toList();
        var rowSecurityPredicates = rowSecurityResolutionService
                .resolveApplicable(organizationId, datasourceId, userId).stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();

        // 3. Execute via the proxy executor — RLS rewrite + post-fetch masking + row cap + timeout.
        return queryExecutor.sampleTable(new SampleTableRequest(datasourceId, target.schema(),
                target.table(), restrictedColumns, columnMasks, rowSecurityPredicates, limit, null));
    }

    /** Canonical schema/table names taken from the introspected view (DB casing preserved). */
    private record Target(String schema, String table) {
    }

    private Optional<Target> resolveTarget(DatabaseSchemaView view, String schema, String table) {
        for (var ns : view.schemas()) {
            if (schema != null && !schema.isBlank() && !ns.name().equalsIgnoreCase(schema)) {
                continue;
            }
            for (var t : ns.tables()) {
                if (t.name().equalsIgnoreCase(table)) {
                    return Optional.of(new Target(ns.name(), t.name()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Mirrors the allow-list semantics of {@code DefaultQuerySubmissionService.verifyAllowedTables}:
     * empty lists allow everything; otherwise the table (bare or {@code schema.table}) must be in
     * {@code allowedTables}, or its schema in {@code allowedSchemas}.
     */
    private static boolean targetAllowed(DatasourceUserPermissionView permission, Target target) {
        var allowedSchemas = normalizeList(permission.allowedSchemas());
        var allowedTables = normalizeList(permission.allowedTables());
        if (allowedSchemas.isEmpty() && allowedTables.isEmpty()) {
            return true;
        }
        var bare = normalize(target.table());
        var qualified = target.schema() == null || target.schema().isBlank()
                ? bare
                : normalize(target.schema()) + "." + bare;
        if (allowedTables.contains(bare) || allowedTables.contains(qualified)) {
            return true;
        }
        return target.schema() != null && !target.schema().isBlank()
                && allowedSchemas.contains(normalize(target.schema()));
    }

    private static List<String> normalizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(raw.size());
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            var normalized = normalize(entry);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static String normalize(String raw) {
        var stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            stripped.append(c);
        }
        return stripped.toString().trim().toLowerCase(Locale.ROOT);
    }
}
