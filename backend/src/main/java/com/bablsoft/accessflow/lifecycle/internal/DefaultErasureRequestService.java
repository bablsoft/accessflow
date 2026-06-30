package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestInvalidStateException;
import com.bablsoft.accessflow.lifecycle.api.DeletionRequestNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestService;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public ErasureRequestView submit(SubmitErasureCommand command) {
        var entity = new DeletionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDatasourceId(command.datasourceId());
        entity.setSubjectType(command.subjectType());
        entity.setSubjectIdentifier(command.subjectIdentifier());
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
}
