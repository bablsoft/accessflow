package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.partqam.accessflow.core.api.DatasourceUserPermissionView;
import com.partqam.accessflow.core.api.DatasourceView;
import com.partqam.accessflow.core.api.QueryRequestPersistenceService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.SubmitQueryCommand;
import com.partqam.accessflow.core.events.QuerySubmittedEvent;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.proxy.api.SqlParserService;
import com.partqam.accessflow.workflow.api.QuerySubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
class DefaultQuerySubmissionService implements QuerySubmissionService {

    private final SqlParserService sqlParserService;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final QueryRequestPersistenceService queryRequestPersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    @Transactional
    public QuerySubmissionResult submit(SubmissionInput input) {
        var parsed = sqlParserService.parse(input.sql());
        if (parsed.type() == QueryType.OTHER) {
            throw new InvalidSqlException(msg("error.query_type_not_supported"));
        }
        var datasource = resolveDatasource(input);
        if (!datasource.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        if (!input.isAdmin()) {
            verifyPermission(input.submitterUserId(), datasource.id(), parsed.type());
        }
        var id = queryRequestPersistenceService.submit(new SubmitQueryCommand(
                datasource.id(),
                input.submitterUserId(),
                input.sql(),
                parsed.type(),
                input.justification()));
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
                                  QueryType queryType) {
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
