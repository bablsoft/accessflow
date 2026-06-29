package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

        // 3. Same permission model as a real submission: capability + allow-list for non-admins.
        if (!isAdmin) {
            verifyPermission(userId, datasourceId, parsed.type(), parsed.referencedTables());
        }

        // 4. Resolve the caller's row-security directives so the plan reflects the governed query.
        var rowSecurityPredicates = rowSecurityResolutionService
                .resolveApplicable(organizationId, datasourceId, userId).stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();

        var request = new QueryExecutionRequest(datasourceId, sql, parsed.type(), null, null,
                List.of(), List.of(), rowSecurityPredicates, false, null, List.of());
        var result = queryExecutor.dryRun(request);

        // 5. Localize the unsupported reason for engines that cannot produce a plan.
        if (!result.supported() && result.unsupportedReason() == null) {
            return result.withUnsupportedReason(msg("error.dry_run.unsupported",
                    new Object[]{result.engineId()}));
        }
        return result;
    }

    private void verifyPermission(UUID userId, UUID datasourceId, QueryType queryType,
                                  Set<String> referencedTables) {
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
        verifyAllowedTables(permission, datasourceId, referencedTables);
    }

    private void verifyAllowedTables(DatasourceUserPermissionView permission, UUID datasourceId,
                                     Set<String> referencedTables) {
        var allowedSchemas = normalizeList(permission.allowedSchemas());
        var allowedTables = normalizeList(permission.allowedTables());
        if (allowedSchemas.isEmpty() && allowedTables.isEmpty()) {
            return;
        }
        if (referencedTables == null || referencedTables.isEmpty()) {
            return;
        }
        var rejected = new TreeSet<String>();
        for (String table : referencedTables) {
            if (allowedTables.contains(table)) {
                continue;
            }
            int dotIdx = table.indexOf('.');
            if (dotIdx > 0 && allowedSchemas.contains(table.substring(0, dotIdx))) {
                continue;
            }
            rejected.add(table);
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

    private static List<String> normalizeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<String>(raw.size());
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            var stripped = new StringBuilder(entry.length());
            for (int i = 0; i < entry.length(); i++) {
                char c = entry.charAt(i);
                if (c == '"' || c == '`' || c == '[' || c == ']') {
                    continue;
                }
                stripped.append(c);
            }
            var normalized = stripped.toString().trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
