package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestService;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestSubmittedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultErasureRequestService implements ErasureRequestService {

    private final DeletionRequestRepository repository;
    private final ErasureRequestViewMapper mapper;
    private final ErasureConditionValidator conditionValidator;
    private final ErasureConditionCodec conditionCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public ErasureRequestView submit(SubmitErasureCommand command) {
        validateShape(command);
        conditionValidator.validate(command.datasourceId(), command.targetTable(),
                command.conditions(), command.rawWhere(), null);
        var entity = new DeletionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDatasourceId(command.datasourceId());
        entity.setSubjectType(command.subjectType());
        entity.setSubjectIdentifier(blankToNull(command.subjectIdentifier()));
        entity.setTargetTable(blankToNull(command.targetTable()));
        entity.setTargetColumns(command.targetColumns() == null ? new String[0]
                : command.targetColumns().toArray(String[]::new));
        entity.setConditions(conditionCodec.toJson(command.conditions()));
        entity.setRawWhere(blankToNull(command.rawWhere()));
        entity.setReason(command.reason());
        entity.setRequestedBy(command.requestedBy());
        entity.setStatus(ErasureStatus.PENDING_SCOPE_AI);
        Instant now = clock.instant();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new ErasureRequestSubmittedEvent(
                saved.getId(), saved.getOrganizationId(), saved.getDatasourceId()));
        return mapper.toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ErasureRequestView> listMine(UUID organizationId, UUID requesterId,
                                                     PageRequest pageRequest) {
        var page = repository.findAllByOrganizationIdAndRequestedBy(organizationId, requesterId,
                LifecyclePageAdapter.toSpringPageable(pageRequest));
        return LifecyclePageAdapter.toPageResponse(page).map(mapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public ErasureRequestView get(UUID requestId, UUID organizationId) {
        return mapper.toView(load(requestId, organizationId));
    }

    @Override
    @Transactional
    public void cancel(UUID requestId, UUID requesterId, UUID organizationId) {
        var entity = repository.findByIdForUpdate(requestId)
                .filter(e -> e.getOrganizationId().equals(organizationId)
                        && e.getRequestedBy().equals(requesterId))
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
        if (entity.getStatus() != ErasureStatus.PENDING_SCOPE_AI
                && entity.getStatus() != ErasureStatus.PENDING_REVIEW) {
            throw new DeletionRequestInvalidStateException(entity.getStatus());
        }
        entity.setStatus(ErasureStatus.CANCELLED);
        repository.save(entity);
    }

    private DeletionRequestEntity load(UUID requestId, UUID organizationId) {
        return repository.findByIdAndOrganizationId(requestId, organizationId)
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
    }

    private static void validateShape(SubmitErasureCommand command) {
        boolean hasSubject = command.subjectIdentifier() != null
                && !command.subjectIdentifier().isBlank();
        boolean hasStructured = command.conditions() != null && !command.conditions().isEmpty();
        boolean hasRawWhere = command.rawWhere() != null && !command.rawWhere().isBlank();
        if (!hasSubject && !hasStructured && !hasRawWhere) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.EMPTY_REQUEST);
        }
        if ((hasStructured || hasRawWhere)
                && (command.targetTable() == null || command.targetTable().isBlank())) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.TARGET_TABLE_REQUIRED);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
