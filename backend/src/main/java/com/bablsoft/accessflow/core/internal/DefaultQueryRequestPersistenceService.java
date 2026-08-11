package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestPersistenceService implements QueryRequestPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultQueryRequestPersistenceService.class);

    private final QueryRequestRepository queryRequestRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public UUID submit(SubmitQueryCommand command) {
        var datasource = datasourceRepository.findById(command.datasourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "Datasource not found: " + command.datasourceId()));
        var submitter = userRepository.findById(command.submittedByUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User not found: " + command.submittedByUserId()));
        var entity = new QueryRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText(command.sqlText());
        entity.setQueryType(command.queryType());
        entity.setTransactional(command.transactional());
        entity.setJustification(command.justification());
        entity.setScheduledFor(command.scheduledFor());
        if (command.submissionReason() != null) {
            entity.setSubmissionReason(command.submissionReason());
        }
        entity.setSubmittedIp(command.submittedIp());
        entity.setSubmittedUserAgent(command.submittedUserAgent());
        entity.setCiCdOrigin(command.ciCdOrigin());
        entity.setRecurrenceRule(command.recurrenceRule());
        entity.setRecurrenceUntil(command.recurrenceUntil());
        entity.setRecurrenceNextRunAt(command.recurrenceNextRunAt());
        var saved = queryRequestRepository.save(entity);
        return saved.getId();
    }

    @Override
    @Transactional
    public Optional<UUID> createRecurringOccurrence(UUID parentId, Instant nextRunAt) {
        var parent = queryRequestRepository.findByIdForUpdate(parentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Recurring parent not found: " + parentId));
        // A cancel or fail-closed halt may have raced the job's due-scan; under the lock the
        // cursor is authoritative, so an ineligible parent simply produces no occurrence.
        if (parent.getStatus() != QueryStatus.APPROVED || parent.getRecurrenceNextRunAt() == null) {
            log.debug("Skipping recurring occurrence for {}: status={}, cursor={}",
                    parentId, parent.getStatus(), parent.getRecurrenceNextRunAt());
            return Optional.empty();
        }
        var child = new QueryRequestEntity();
        child.setId(UUID.randomUUID());
        child.setDatasource(parent.getDatasource());
        child.setSubmittedBy(parent.getSubmittedBy());
        child.setSqlText(parent.getSqlText());
        child.setQueryType(parent.getQueryType());
        child.setTransactional(parent.isTransactional());
        child.setJustification(parent.getJustification());
        child.setStatus(QueryStatus.APPROVED);
        child.setSubmissionReason(SubmissionReason.RECURRING);
        child.setRecurringParentId(parent.getId());
        var saved = queryRequestRepository.save(child);
        // Advance-before-execute: the cursor moves in the same transaction as the child insert,
        // so a crash mid-execution can never re-fire the same occurrence.
        parent.setRecurrenceNextRunAt(nextRunAt);
        return Optional.of(saved.getId());
    }

    @Override
    @Transactional
    public void clearRecurrenceNextRun(UUID parentId, String haltedReason) {
        var parent = queryRequestRepository.findByIdForUpdate(parentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Recurring parent not found: " + parentId));
        parent.setRecurrenceNextRunAt(null);
        if (haltedReason != null) {
            parent.setRecurrenceHaltedReason(haltedReason);
        }
    }
}
