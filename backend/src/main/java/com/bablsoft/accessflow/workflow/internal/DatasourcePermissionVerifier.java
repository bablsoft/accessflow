package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * The per-user datasource permission gate shared by query submission and the per-occurrence
 * fail-closed recheck of recurring series (#627): an active (unexpired) permission row must exist,
 * grant the capability matching the query type, and cover every referenced table with its
 * schema/table allow-list. Extracted verbatim from {@code DefaultQuerySubmissionService} so the
 * recurring path re-evaluates the exact same rules with current permission state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class DatasourcePermissionVerifier {

    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MessageSource messageSource;
    private final Clock clock;

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /**
     * @throws AccessDeniedException when the user has no active permission on the datasource, the
     *         permission is expired, lacks the capability for {@code queryType}, or any referenced
     *         table falls outside the allow-list.
     */
    void verify(UUID userId, UUID datasourceId, QueryType queryType,
                Set<String> referencedTables) {
        var permission = permissionLookupService.findFor(userId, datasourceId)
                .orElseThrow(() -> new AccessDeniedException(
                        "No active permission on datasource: " + datasourceId));
        if (permission.expiresAt() != null && permission.expiresAt().isBefore(clock.instant())) {
            throw new AccessDeniedException("Permission expired for datasource: " + datasourceId);
        }
        if (!DatasourcePermissionChecker.hasCapability(permission, queryType)) {
            throw new AccessDeniedException(
                    "Insufficient permission for " + queryType + " on datasource: " + datasourceId);
        }
        verifyAllowedTables(permission, datasourceId, referencedTables);
    }

    private void verifyAllowedTables(DatasourceUserPermissionView permission,
                                     UUID datasourceId, Set<String> referencedTables) {
        var rejected = DatasourcePermissionChecker.rejectedTables(permission, referencedTables);
        if (!rejected.isEmpty()) {
            String joined = String.join(", ", rejected);
            log.warn("Allow-list rejection on datasource {} for user {}: tables {} not allowed",
                    datasourceId, permission.userId(), rejected);
            throw new AccessDeniedException(
                    msg("error.permission.table_not_allowed", new Object[]{joined}));
        }
    }
}
