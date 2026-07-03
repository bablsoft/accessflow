package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestNotCancellableException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.api.AccessRequestService;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.InvalidAccessDurationException;
import com.bablsoft.accessflow.access.events.AccessRequestSubmittedEvent;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAccessRequestService implements AccessRequestService {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;
    private final AccessRequestViewMapper viewMapper;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final AccessProperties properties;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional
    public AccessRequestView submit(SubmitCommand command) {
        requireRequestableDatasource(command.organizationId(), command.datasourceId());
        validateDuration(command.requestedDuration());
        var entity = new AccessGrantRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setRequesterId(command.requesterId());
        entity.setDatasourceId(command.datasourceId());
        entity.setCanRead(command.canRead());
        entity.setCanWrite(command.canWrite());
        entity.setCanDdl(command.canDdl());
        entity.setAllowedSchemas(toArray(command.allowedSchemas()));
        entity.setAllowedTables(toArray(command.allowedTables()));
        entity.setRequestedDuration(command.requestedDuration());
        entity.setJustification(command.justification());
        entity.setPreApproveQueries(command.preApproveQueries());
        entity.setStatus(AccessGrantStatus.PENDING);
        var saved = requestRepository.save(entity);
        eventPublisher.publishEvent(
                new AccessRequestSubmittedEvent(saved.getId(), saved.getRequesterId()));
        return viewMapper.toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AccessRequestView> listMine(UUID organizationId, UUID requesterId,
                                                    AccessGrantStatus statusFilter,
                                                    PageRequest pageRequest) {
        var spec = AccessRequestSpecifications.mine(requesterId, organizationId, statusFilter);
        var page = requestRepository.findAll(spec, withDefaultSort(pageRequest));
        return AccessPageAdapter.toPageResponse(page.map(viewMapper::toView));
    }

    @Override
    @Transactional
    public void cancel(UUID accessRequestId, UUID requesterId, UUID organizationId) {
        var entity = requestRepository.findById(accessRequestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(accessRequestId));
        if (!entity.getOrganizationId().equals(organizationId)
                || !entity.getRequesterId().equals(requesterId)) {
            // Do not leak the existence of another user's request.
            throw new AccessRequestNotFoundException(accessRequestId);
        }
        if (entity.getStatus() != AccessGrantStatus.PENDING) {
            throw new AccessRequestNotCancellableException(accessRequestId, entity.getStatus());
        }
        // The state service flips PENDING → CANCELLED and publishes AccessRequestStatusChangedEvent,
        // which the realtime module fans out to the requester. No separate cancel notification.
        stateService.cancel(accessRequestId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourceOption> listRequestableDatasources(UUID organizationId) {
        return datasourceLookupService.findActiveRefsByOrganization(organizationId).stream()
                .map(ref -> new DatasourceOption(ref.id(), ref.name()))
                .toList();
    }

    @Override
    public DatabaseSchemaView introspectRequestableDatasourceSchema(UUID datasourceId,
                                                                    UUID organizationId) {
        // introspectSchemaForSystem is org-scoped (loadInOrganization) and not permission-gated —
        // a JIT requester does not yet hold a permission on the datasource. It manages its own
        // REQUIRES_NEW read-only transaction, so no @Transactional is needed here.
        return datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId);
    }

    private void requireRequestableDatasource(UUID organizationId, UUID datasourceId) {
        var requestable = datasourceLookupService.findActiveRefsByOrganization(organizationId)
                .stream()
                .map(DatasourceRef::id)
                .anyMatch(id -> id.equals(datasourceId));
        if (!requestable) {
            throw new DatasourceNotFoundException(datasourceId);
        }
    }

    private void validateDuration(String iso8601) {
        Duration duration;
        try {
            duration = Duration.parse(iso8601);
        } catch (DateTimeParseException ex) {
            throw new InvalidAccessDurationException(msg("error.access_duration_invalid"));
        }
        if (duration.isNegative() || duration.isZero()
                || duration.compareTo(properties.minDuration()) < 0
                || duration.compareTo(properties.maxDuration()) > 0) {
            throw new InvalidAccessDurationException(msg("error.access_duration_range",
                    properties.minDuration().toString(), properties.maxDuration().toString()));
        }
    }

    private Pageable withDefaultSort(PageRequest pageRequest) {
        var pageable = AccessPageAdapter.toSpringPageable(pageRequest);
        if (pageable.getSort().isUnsorted()) {
            return org.springframework.data.domain.PageRequest.of(
                    pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        return pageable;
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private static String[] toArray(List<String> values) {
        return (values == null || values.isEmpty()) ? null : values.toArray(String[]::new);
    }
}
