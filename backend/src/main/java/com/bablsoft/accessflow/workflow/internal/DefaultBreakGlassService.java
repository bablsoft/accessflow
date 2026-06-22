package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.BreakGlassNotPermittedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.events.BreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.BreakGlassEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultBreakGlassService implements BreakGlassService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBreakGlassService.class);

    private final QueryParser queryParser;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final QuotaService quotaService;
    private final QueryRequestPersistenceService queryRequestPersistenceService;
    private final QueryRequestStateService queryRequestStateService;
    private final QueryLifecycleService queryLifecycleService;
    private final BreakGlassEventRepository breakGlassEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    @Transactional
    public BreakGlassResult breakGlassExecute(BreakGlassInput input) {
        var datasource = resolveDatasource(input);
        if (!datasource.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        quotaService.checkQueryQuota(input.organizationId());
        var parsed = queryParser.parse(input.sql(), datasource.dbType());
        if (parsed.type() == QueryType.OTHER) {
            throw new InvalidSqlException(msg("error.query_type_not_supported"));
        }
        // The break-glass grant is required for everyone — including admins (AF-385).
        verifyBreakGlassPermission(input.submitterUserId(), datasource.id(), parsed.type(),
                parsed.referencedTables());

        // Persist as EMERGENCY_ACCESS WITHOUT publishing QuerySubmittedEvent — AI analysis and human
        // review are intentionally bypassed. The query is then force-approved and executed inline.
        var queryId = queryRequestPersistenceService.submit(new SubmitQueryCommand(
                datasource.id(),
                input.submitterUserId(),
                input.sql(),
                parsed.type(),
                parsed.transactional(),
                input.justification(),
                null,
                SubmissionReason.EMERGENCY_ACCESS,
                input.submittedIp(),
                input.submittedUserAgent(),
                false));
        queryRequestStateService.transitionTo(queryId, QueryStatus.PENDING_AI, QueryStatus.APPROVED);

        var event = new BreakGlassEventEntity();
        event.setId(UUID.randomUUID());
        event.setQueryRequestId(queryId);
        event.setOrganizationId(input.organizationId());
        event.setDatasourceId(datasource.id());
        event.setSubmittedBy(input.submitterUserId());
        event.setJustification(input.justification());
        event.setStatus(BreakGlassStatus.PENDING_REVIEW);
        breakGlassEventRepository.save(event);

        var outcome = queryLifecycleService.executeBreakGlass(queryId, input.submitterUserId());

        eventPublisher.publishEvent(new BreakGlassExecutedEvent(
                event.getId(), queryId, input.organizationId()));
        log.warn("Break-glass execution by user {} on datasource {} — query {} ({}), event {}",
                input.submitterUserId(), datasource.id(), queryId, outcome.status(), event.getId());
        return new BreakGlassResult(queryId, event.getId(), outcome.status(),
                outcome.rowsAffected(), outcome.durationMs());
    }

    private DatasourceView resolveDatasource(BreakGlassInput input) {
        return input.isAdmin()
                ? datasourceAdminService.getForAdmin(input.datasourceId(), input.organizationId())
                : datasourceAdminService.getForUser(input.datasourceId(), input.organizationId(),
                        input.submitterUserId());
    }

    private void verifyBreakGlassPermission(UUID userId, UUID datasourceId, QueryType queryType,
                                            Set<String> referencedTables) {
        var permission = permissionLookupService.findFor(userId, datasourceId)
                .orElseThrow(() -> denied(datasourceId, userId, "no permission"));
        if (!permission.canBreakGlass()) {
            throw denied(datasourceId, userId, "can_break_glass not granted");
        }
        if (permission.expiresAt() != null && permission.expiresAt().isBefore(Instant.now())) {
            throw denied(datasourceId, userId, "grant expired");
        }
        if (!DatasourcePermissionChecker.hasCapability(permission, queryType)) {
            throw denied(datasourceId, userId, "missing " + queryType + " capability");
        }
        if (!DatasourcePermissionChecker.rejectedTables(permission, referencedTables).isEmpty()) {
            throw denied(datasourceId, userId, "tables outside allow-list");
        }
    }

    private static BreakGlassNotPermittedException denied(UUID datasourceId, UUID userId,
                                                         String reason) {
        log.warn("Break-glass denied for user {} on datasource {}: {}", userId, datasourceId, reason);
        return new BreakGlassNotPermittedException(datasourceId);
    }
}
