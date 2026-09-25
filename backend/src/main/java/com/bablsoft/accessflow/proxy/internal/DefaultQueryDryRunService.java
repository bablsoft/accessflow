package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.AllowedTables;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DeniedColumns;
import com.bablsoft.accessflow.core.api.DeniedShapes;
import com.bablsoft.accessflow.core.api.DeniedTables;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.proxy.api.QueryDryRunService;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryDryRunService implements QueryDryRunService {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryDryRunService.class);

    private final QueryParser queryParser;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final QueryExecutor queryExecutor;
    private final MessageSource messageSource;

    @Override
    public QueryDryRunResult dryRun(UUID datasourceId, String sql, UUID userId,
                                    UUID organizationId, boolean isAdmin) {
        // 1. Authorization. getForUser/getForAdmin enforces org + permission-row access
        //    (DatasourceNotFoundException -> 404) and hands back the dbType for parsing.
        var datasource = isAdmin
                ? datasourceAdminService.getForAdmin(datasourceId, organizationId)
                : datasourceAdminService.getForUser(datasourceId, organizationId, userId);

        // 2. Parse (InvalidSqlException -> 422) for the query type + referenced-table allow-list keys.
        var parsed = queryParser.parse(sql, datasource.dbType());

        // 3. Same permission model as a real submission: capability + allow-list + column deny list
        //    for non-admins.
        if (!isAdmin) {
            verifyPermission(userId, datasourceId, parsed);
        }

        // 4. Resolve the caller's row-security directives so the plan reflects the governed query.
        var rowSecurityPredicates = rowSecurityResolutionService
                .resolveApplicable(organizationId, datasourceId, userId).stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();

        // Carry the parser's transactional verdict through: the executor refuses to plan a
        // BEGIN; … COMMIT; envelope rather than passing the stacked text to a dialect planner.
        var request = new QueryExecutionRequest(datasourceId, sql, parsed.type(), null, null,
                List.of(), List.of(), rowSecurityPredicates,
                parsed.transactional(), parsed.statements(), List.of());
        var result = queryExecutor.dryRun(request);

        // 5. Localize the unsupported reason for engines that cannot produce a plan.
        if (!result.supported() && result.unsupportedReason() == null) {
            return result.withUnsupportedReason(msg("error.dry_run.unsupported",
                    new Object[]{result.engineId()}));
        }
        return result;
    }

    private void verifyPermission(UUID userId, UUID datasourceId, SqlParseResult parsed) {
        var queryType = parsed.type();
        var permission = permissionLookupService.findFor(userId, datasourceId)
                .orElseThrow(() -> new AccessDeniedException(
                        "No active permission on datasource: " + datasourceId));
        if (permission.expiresAt() != null
                && permission.expiresAt().isBefore(java.time.Instant.now())) {
            throw new AccessDeniedException("Permission expired for datasource: " + datasourceId);
        }
        if (!hasCapability(permission, queryType)) {
            throw new AccessDeniedException(
                    "Insufficient permission for " + queryType + " on datasource: " + datasourceId);
        }
        verifyAllowedTables(permission, datasourceId, parsed.referencedTables());
        var deniedTables = DeniedTables.rejected(permission.deniedSchemas(),
                permission.deniedTables(), parsed.referencedTables());
        if (!deniedTables.isEmpty()) {
            log.warn("Dry-run table deny rejection on datasource {} for user {}: tables {}",
                    datasourceId, permission.userId(), deniedTables);
            throw new AccessDeniedException(msg("error.permission.table_denied",
                    new Object[]{String.join(", ", deniedTables)}));
        }
        var deniedColumns = DeniedColumns.rejected(permission.deniedColumns(), parsed);
        if (!deniedColumns.isEmpty()) {
            log.warn("Dry-run column deny rejection on datasource {} for user {}: columns {}",
                    datasourceId, permission.userId(), deniedColumns);
            throw new AccessDeniedException(msg("error.permission.column_not_allowed",
                    new Object[]{String.join(", ", deniedColumns)}));
        }
        var deniedShapes = DeniedShapes.rejected(permission.deniedShapes(), parsed);
        if (!deniedShapes.isEmpty()) {
            log.warn("Dry-run query shape rejection on datasource {} for user {}: shapes {}",
                    datasourceId, permission.userId(), deniedShapes);
            throw new AccessDeniedException(msg("error.permission.shape_denied",
                    new Object[]{String.join(", ", deniedShapes.stream().map(Enum::name).toList())}));
        }
    }

    private void verifyAllowedTables(DatasourceUserPermissionView permission, UUID datasourceId,
                                     Set<String> referencedTables) {
        var allowedSchemas = AllowedTables.normalize(permission.allowedSchemas());
        var allowedTables = AllowedTables.normalize(permission.allowedTables());
        if (allowedSchemas.isEmpty() && allowedTables.isEmpty()) {
            return;
        }
        if (referencedTables == null || referencedTables.isEmpty()) {
            return;
        }
        var rejected = new TreeSet<String>();
        for (String table : referencedTables) {
            if (AllowedTables.coveringEntry(allowedSchemas, allowedTables, table) == null) {
                rejected.add(table);
            }
        }
        if (!rejected.isEmpty()) {
            log.warn("Dry-run allow-list rejection on datasource {} for user {}: tables {}",
                    datasourceId, permission.userId(), rejected);
            throw new AccessDeniedException(msg("error.permission.table_not_allowed",
                    new Object[]{String.join(", ", rejected)}));
        }
    }

    private static boolean hasCapability(DatasourceUserPermissionView permission, QueryType type) {
        return switch (type) {
            case SELECT -> permission.canRead();
            case INSERT, UPDATE, DELETE -> permission.canWrite();
            case DDL -> permission.canDdl();
            case OTHER -> false;
        };
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
