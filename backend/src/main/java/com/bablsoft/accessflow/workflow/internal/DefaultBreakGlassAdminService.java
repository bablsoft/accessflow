package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.workflow.api.BreakGlassAdminService;
import com.bablsoft.accessflow.workflow.api.BreakGlassAlreadyReviewedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventFilter;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventNotFoundException;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventView;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import com.bablsoft.accessflow.workflow.api.SelfAcknowledgeNotAllowedException;
import com.bablsoft.accessflow.workflow.events.BreakGlassReviewedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.BreakGlassEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultBreakGlassAdminService implements BreakGlassAdminService {

    private final BreakGlassEventRepository breakGlassEventRepository;
    private final QueryRequestLookupService queryRequestLookupService;
    private final DatasourceLookupService datasourceLookupService;
    private final UserQueryService userQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BreakGlassEventView> list(UUID organizationId, BreakGlassEventFilter filter,
                                                  PageRequest pageRequest) {
        var spec = BreakGlassEventSpecifications.forQuery(organizationId,
                filter == null ? BreakGlassEventFilter.empty() : filter);
        var page = breakGlassEventRepository.findAll(spec, toSpringPageable(pageRequest))
                .map(this::toView);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public BreakGlassEventView get(UUID organizationId, UUID eventId) {
        return toView(loadOrThrow(organizationId, eventId));
    }

    @Override
    @Transactional
    public BreakGlassEventView acknowledge(UUID organizationId, UUID eventId, UUID actorUserId,
                                           String comment) {
        var entity = loadOrThrow(organizationId, eventId);
        if (entity.getSubmittedBy().equals(actorUserId)) {
            throw new SelfAcknowledgeNotAllowedException(eventId);
        }
        if (entity.getStatus() == BreakGlassStatus.REVIEWED) {
            throw new BreakGlassAlreadyReviewedException(eventId);
        }
        entity.setStatus(BreakGlassStatus.REVIEWED);
        entity.setReviewedBy(actorUserId);
        entity.setReviewComment(comment != null && comment.isBlank() ? null : comment);
        entity.setReviewedAt(Instant.now());
        var saved = breakGlassEventRepository.save(entity);
        eventPublisher.publishEvent(new BreakGlassReviewedEvent(
                eventId, entity.getQueryRequestId(), organizationId, actorUserId));
        return toView(saved);
    }

    private BreakGlassEventEntity loadOrThrow(UUID organizationId, UUID eventId) {
        return breakGlassEventRepository.findByIdAndOrganizationId(eventId, organizationId)
                .orElseThrow(() -> new BreakGlassEventNotFoundException(eventId));
    }

    private BreakGlassEventView toView(BreakGlassEventEntity entity) {
        var query = queryRequestLookupService.findById(entity.getQueryRequestId()).orElse(null);
        var datasource = datasourceLookupService.findRef(entity.getDatasourceId()).orElse(null);
        var submitter = userQueryService.findById(entity.getSubmittedBy()).orElse(null);
        var reviewer = entity.getReviewedBy() == null
                ? null
                : userQueryService.findById(entity.getReviewedBy()).orElse(null);
        QueryStatus executionStatus = query == null ? null : query.status();
        return new BreakGlassEventView(
                entity.getId(),
                entity.getQueryRequestId(),
                entity.getOrganizationId(),
                entity.getDatasourceId(),
                datasource != null ? datasource.name() : null,
                entity.getSubmittedBy(),
                submitter != null ? submitter.displayName() : null,
                submitter != null ? submitter.email() : null,
                query != null ? query.sqlText() : null,
                executionStatus,
                entity.getJustification(),
                entity.getStatus(),
                entity.getReviewedBy(),
                reviewer != null ? reviewer.displayName() : null,
                entity.getReviewComment(),
                entity.getReviewedAt(),
                entity.getCreatedAt());
    }

    private static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        var sort = request.sort().isEmpty()
                ? Sort.unsorted()
                : Sort.by(request.sort().stream()
                        .map(DefaultBreakGlassAdminService::toSpringOrder).toList());
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
