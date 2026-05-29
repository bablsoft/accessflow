package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateAccessDeniedException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNameAlreadyExistsException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateService;
import com.bablsoft.accessflow.workflow.api.QueryTemplateView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DefaultQueryTemplateService implements QueryTemplateService {

    private final QueryTemplateRepository queryTemplateRepository;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueryTemplateView> list(UUID organizationId, UUID callerUserId,
                                                QueryTemplateFilter filter, PageRequest pageRequest) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (callerUserId == null) {
            throw new IllegalArgumentException("callerUserId is required");
        }
        var spec = QueryTemplateSpecifications.forList(organizationId, callerUserId, filter);
        var page = queryTemplateRepository.findAll(spec, toSpringPageable(pageRequest));
        Map<UUID, String> ownerNames = resolveOwnerNames(page.getContent());
        var mapped = page.map(entity -> QueryTemplateMapper.toView(entity,
                ownerNames.getOrDefault(entity.getOwnerId(), null)));
        return new PageResponse<>(mapped.getContent(), mapped.getNumber(), mapped.getSize(),
                mapped.getTotalElements(), mapped.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public QueryTemplateView get(UUID id, UUID organizationId, UUID callerUserId) {
        var entity = loadVisibleOrThrow(id, organizationId, callerUserId);
        return QueryTemplateMapper.toView(entity, resolveOwnerName(entity.getOwnerId()));
    }

    @Override
    @Transactional
    public QueryTemplateView create(CreateQueryTemplateCommand command) {
        if (command.organizationId() == null || command.ownerId() == null) {
            throw new IllegalArgumentException("organizationId and ownerId are required");
        }
        String normalizedName = command.name() == null ? null : command.name().trim();
        queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(
                        command.organizationId(), command.ownerId(), normalizedName)
                .ifPresent(existing -> {
                    throw new QueryTemplateNameAlreadyExistsException(existing.getName());
                });
        var entity = new QueryTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setOwnerId(command.ownerId());
        entity.setDatasourceId(command.datasourceId());
        entity.setName(normalizedName);
        entity.setBody(command.body());
        entity.setDescription(command.description());
        entity.setTags(toArray(command.tags()));
        entity.setVisibility(command.visibility());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        var saved = queryTemplateRepository.save(entity);
        return QueryTemplateMapper.toView(saved, resolveOwnerName(saved.getOwnerId()));
    }

    @Override
    @Transactional
    public QueryTemplateView update(UUID id, UUID organizationId, UUID callerUserId,
                                    UpdateQueryTemplateCommand command) {
        var entity = loadVisibleOrThrow(id, organizationId, callerUserId);
        if (!entity.getOwnerId().equals(callerUserId)) {
            throw new QueryTemplateAccessDeniedException(id);
        }
        if (command.name() != null) {
            String trimmed = command.name().trim();
            if (!trimmed.equalsIgnoreCase(entity.getName())) {
                queryTemplateRepository.findByOrganizationIdAndOwnerIdAndNameIgnoreCase(
                                organizationId, callerUserId, trimmed)
                        .filter(other -> !other.getId().equals(id))
                        .ifPresent(other -> {
                            throw new QueryTemplateNameAlreadyExistsException(other.getName());
                        });
            }
            entity.setName(trimmed);
        }
        if (command.body() != null) {
            entity.setBody(command.body());
        }
        if (command.description() != null) {
            entity.setDescription(command.description());
        }
        if (command.tags() != null) {
            entity.setTags(toArray(command.tags()));
        }
        if (command.visibility() != null) {
            entity.setVisibility(command.visibility());
        }
        entity.setDatasourceId(command.datasourceId());
        entity.setUpdatedAt(Instant.now());
        return QueryTemplateMapper.toView(entity, resolveOwnerName(entity.getOwnerId()));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId, UUID callerUserId) {
        var entity = loadVisibleOrThrow(id, organizationId, callerUserId);
        if (!entity.getOwnerId().equals(callerUserId)) {
            throw new QueryTemplateAccessDeniedException(id);
        }
        queryTemplateRepository.delete(entity);
    }

    private QueryTemplateEntity loadVisibleOrThrow(UUID id, UUID organizationId, UUID callerUserId) {
        var entity = queryTemplateRepository.findById(id)
                .orElseThrow(() -> new QueryTemplateNotFoundException(id));
        if (!entity.getOrganizationId().equals(organizationId)) {
            throw new QueryTemplateNotFoundException(id);
        }
        if (entity.getVisibility() == QueryTemplateVisibility.PRIVATE
                && !entity.getOwnerId().equals(callerUserId)) {
            throw new QueryTemplateNotFoundException(id);
        }
        return entity;
    }

    private Map<UUID, String> resolveOwnerNames(List<QueryTemplateEntity> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        return entities.stream()
                .map(QueryTemplateEntity::getOwnerId)
                .distinct()
                .map(userQueryService::findById)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(UserView::id, UserView::displayName, (a, b) -> a));
    }

    private String resolveOwnerName(UUID ownerId) {
        return userQueryService.findById(ownerId)
                .map(UserView::displayName)
                .orElse(null);
    }

    private static String[] toArray(List<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    private static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        var sort = request.sort().isEmpty()
                ? Sort.unsorted()
                : Sort.by(request.sort().stream().map(DefaultQueryTemplateService::toSpringOrder).toList());
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
