package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
class DefaultQuerySubmissionService implements QuerySubmissionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultQuerySubmissionService.class);

    private final QueryParser queryParser;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final QueryRequestPersistenceService queryRequestPersistenceService;
    private final QuotaService quotaService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @Override
    @Transactional
    public QuerySubmissionResult submit(SubmissionInput input) {
        var datasource = resolveDatasource(input);
        if (!datasource.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        quotaService.checkQueryQuota(input.organizationId());
        var parsed = queryParser.parse(input.sql(), datasource.dbType());
        if (parsed.type() == QueryType.OTHER) {
            throw new InvalidSqlException(msg("error.query_type_not_supported"));
        }
        if (!input.isAdmin()) {
            verifyPermission(input.submitterUserId(), datasource.id(), parsed.type(),
                    parsed.referencedTables());
        }
        var submissionReason = input.submissionReason() != null
                ? input.submissionReason()
                : SubmissionReason.USER_SUBMITTED;
        var id = queryRequestPersistenceService.submit(new SubmitQueryCommand(
                datasource.id(),
                input.submitterUserId(),
                input.sql(),
                parsed.type(),
                parsed.transactional(),
                input.justification(),
                input.scheduledFor(),
                submissionReason));
        eventPublisher.publishEvent(new QuerySubmittedEvent(id));
        return new QuerySubmissionResult(id, QueryStatus.PENDING_AI);
    }

    private DatasourceView resolveDatasource(SubmissionInput input) {
        return input.isAdmin()
                ? datasourceAdminService.getForAdmin(input.datasourceId(), input.organizationId())
                : datasourceAdminService.getForUser(input.datasourceId(), input.organizationId(),
                        input.submitterUserId());
    }

    private void verifyPermission(java.util.UUID userId, java.util.UUID datasourceId,
                                  QueryType queryType, Set<String> referencedTables) {
        var permission = permissionLookupService.findFor(userId, datasourceId)
                .orElseThrow(() -> new AccessDeniedException(
                        "No active permission on datasource: " + datasourceId));
        if (permission.expiresAt() != null && permission.expiresAt().isBefore(Instant.now())) {
            throw new AccessDeniedException("Permission expired for datasource: " + datasourceId);
        }
        if (!hasCapability(permission, queryType)) {
            throw new AccessDeniedException(
                    "Insufficient permission for " + queryType + " on datasource: " + datasourceId);
        }
        verifyAllowedTables(permission, datasourceId, referencedTables);
    }

    private void verifyAllowedTables(DatasourceUserPermissionView permission,
                                     java.util.UUID datasourceId, Set<String> referencedTables) {
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
            if (dotIdx > 0) {
                String schema = table.substring(0, dotIdx);
                if (allowedSchemas.contains(schema)) {
                    continue;
                }
            }
            rejected.add(table);
        }
        if (!rejected.isEmpty()) {
            String joined = String.join(", ", rejected);
            log.warn("Allow-list rejection on datasource {} for user {}: tables {} not in "
                    + "allowed_schemas={} / allowed_tables={}",
                    datasourceId, permission.userId(), rejected, allowedSchemas, allowedTables);
            throw new AccessDeniedException(
                    msg("error.permission.table_not_allowed", new Object[]{joined}));
        }
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

    private static boolean hasCapability(DatasourceUserPermissionView permission, QueryType type) {
        return switch (type) {
            case SELECT -> permission.canRead();
            case INSERT, UPDATE, DELETE -> permission.canWrite();
            case DDL -> permission.canDdl();
            case OTHER -> false;
        };
    }
}
