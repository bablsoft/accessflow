package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DeniedColumns;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.TableNotFoundException;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.SampleDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultSampleDataService implements SampleDataService {

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    private final QueryExecutor queryExecutor;
    private final MessageSource messageSource;

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
            // The preview reads every column, so a denied column on the table refuses it (#935).
            var denied = DeniedColumns.rejectedForWholeTable(view.deniedColumns(),
                    qualifiedName(target));
            if (!denied.isEmpty()) {
                throw new AccessDeniedException(messageSource.getMessage(
                        "error.permission.column_not_allowed",
                        new Object[]{String.join(", ", denied)}, LocaleContextHolder.getLocale()));
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

        // #934: a row-limit policy on the sampled table caps the preview like it caps a query.
        Integer rowLimitOverride = permission.map(DatasourceUserPermissionView::rowLimitOverride)
                .orElse(null);
        var qualified = qualifiedName(target);
        var appliedRowLimit = rowLimitPolicyResolutionService.resolve(organizationId, datasourceId,
                userId, Set.of(qualified));
        if (appliedRowLimit.isPresent()) {
            rowLimitOverride = appliedRowLimit.get().tighten(rowLimitOverride);
        }

        // 3. Execute via the proxy executor — RLS rewrite + post-fetch masking + row cap + timeout.
        return queryExecutor.sampleTable(new SampleTableRequest(datasourceId, target.schema(),
                target.table(), restrictedColumns, columnMasks, rowSecurityPredicates,
                effectiveLimit(limit, rowLimitOverride), null));
    }

    /** The caller's row-limit override (#933) and row-limit policies (#934) cap the preview too, so it can't be used to get around the cap. */
    private static int effectiveLimit(int limit, Integer rowLimitOverride) {
        return rowLimitOverride == null ? limit : Math.min(limit, rowLimitOverride);
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

    private static String qualifiedName(Target target) {
        return target.schema() == null || target.schema().isBlank()
                ? normalize(target.table())
                : normalize(target.schema()) + "." + normalize(target.table());
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
