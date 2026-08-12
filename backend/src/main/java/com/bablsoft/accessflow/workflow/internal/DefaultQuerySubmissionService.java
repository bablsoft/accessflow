package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
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
import com.bablsoft.accessflow.workflow.api.InvalidRecurrenceRuleException;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.internal.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
class DefaultQuerySubmissionService implements QuerySubmissionService {

    private final QueryParser queryParser;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourcePermissionVerifier permissionVerifier;
    private final QueryRequestPersistenceService queryRequestPersistenceService;
    private final QuotaService quotaService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;
    private final WorkflowProperties workflowProperties;
    private final Clock clock;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @Override
    @Transactional
    public QuerySubmissionResult submit(SubmissionInput input) {
        // RECURRING (#627) and EMERGENCY_ACCESS (AF-385) are stamped by the system paths that
        // own them (occurrence insert, break-glass endpoint) — a client supplying one here
        // would pollute the audit trail with a system-only provenance.
        if (input.submissionReason() == SubmissionReason.RECURRING
                || input.submissionReason() == SubmissionReason.EMERGENCY_ACCESS) {
            throw new org.springframework.security.access.AccessDeniedException(
                    msg("error.submission_reason_reserved"));
        }
        var datasource = resolveDatasource(input);
        if (!datasource.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        quotaService.checkQueryQuota(input.organizationId());
        var initialNextRunAt = validateRecurrence(input);
        var parsed = queryParser.parse(input.sql(), datasource.dbType());
        if (parsed.type() == QueryType.OTHER) {
            throw new InvalidSqlException(msg("error.query_type_not_supported"));
        }
        if (!input.isAdmin()) {
            permissionVerifier.verify(input.submitterUserId(), datasource.id(), parsed.type(),
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
                submissionReason,
                input.submittedIp(),
                input.submittedUserAgent(),
                input.ciCdOrigin(),
                input.recurrenceRule(),
                input.recurrenceUntil(),
                initialNextRunAt));
        eventPublisher.publishEvent(new QuerySubmittedEvent(id));
        return new QuerySubmissionResult(id, QueryStatus.PENDING_AI);
    }

    /**
     * Validates the #627 recurrence definition and returns the initial
     * {@code recurrence_next_run_at} cursor ({@code null} for non-recurring submissions). The
     * cursor may predate approval — the recurring job only picks up {@code APPROVED} rows, so a
     * stale first-run instant simply fires on the first tick after approval (documented in
     * docs/04-api-spec.md).
     */
    private Instant validateRecurrence(SubmissionInput input) {
        if (input.recurrenceRule() == null || input.recurrenceRule().isBlank()) {
            if (input.recurrenceUntil() != null) {
                throw new InvalidRecurrenceRuleException(msg("error.recurrence_rule_required"));
            }
            return null;
        }
        if (input.scheduledFor() != null) {
            throw new InvalidRecurrenceRuleException(
                    msg("error.recurrence_scheduled_for_exclusive"));
        }
        if (input.recurrenceUntil() == null) {
            throw new InvalidRecurrenceRuleException(msg("error.recurrence_until_required"));
        }
        var now = clock.instant();
        if (!input.recurrenceUntil().isAfter(now)) {
            throw new InvalidRecurrenceRuleException(msg("error.recurrence_until_not_future"));
        }
        RecurrenceRule rule;
        try {
            rule = RecurrenceRule.parse(input.recurrenceRule());
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            throw new InvalidRecurrenceRuleException(msg("error.recurrence_rule_invalid"));
        }
        var minInterval = workflowProperties.recurrenceMinInterval();
        var gap = rule.minGap(now);
        if (gap != null && gap.compareTo(minInterval) < 0) {
            throw new InvalidRecurrenceRuleException(msg("error.recurrence_interval_too_short",
                    new Object[]{minInterval.toString()}));
        }
        var first = rule.nextAfter(now);
        if (first == null || first.isAfter(input.recurrenceUntil())) {
            throw new InvalidRecurrenceRuleException(
                    msg("error.recurrence_no_occurrence_before_until"));
        }
        return first;
    }

    private DatasourceView resolveDatasource(SubmissionInput input) {
        return input.isAdmin()
                ? datasourceAdminService.getForAdmin(input.datasourceId(), input.organizationId())
                : datasourceAdminService.getForUser(input.datasourceId(), input.organizationId(),
                        input.submitterUserId());
    }
}
