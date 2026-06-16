package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionNotFoundException;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionService;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QueryTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the immutable version history of saved query templates (AF-442): writes a snapshot on every
 * content-changing save (via the package-private {@link QueryTemplateVersionRecorder}) and serves
 * the read side ({@link QueryTemplateVersionService}). Reads enforce visibility against the
 * <em>current</em> parent template — a snapshot's own visibility is never trusted for access control.
 */
@Service
@RequiredArgsConstructor
class DefaultQueryTemplateVersioningService
        implements QueryTemplateVersionService, QueryTemplateVersionRecorder {

    private final QueryTemplateVersionRepository versionRepository;
    private final QueryTemplateRepository templateRepository;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueryTemplateVersionView> listVersions(UUID templateId, UUID organizationId,
                                                               UUID callerUserId, PageRequest pageRequest) {
        requireVisibleTemplate(templateId, organizationId, callerUserId);
        var page = versionRepository.findByTemplateIdOrderByVersionNumberDesc(
                templateId, toSpringPageable(pageRequest));
        Map<UUID, String> authorNames = resolveAuthorNames(page.getContent());
        var mapped = page.map(entity -> QueryTemplateVersionMapper.toView(entity,
                authorNames.getOrDefault(entity.getAuthorId(), null)));
        return new PageResponse<>(mapped.getContent(), mapped.getNumber(), mapped.getSize(),
                mapped.getTotalElements(), mapped.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public QueryTemplateVersionView getVersion(UUID templateId, UUID versionId, UUID organizationId,
                                               UUID callerUserId) {
        requireVisibleTemplate(templateId, organizationId, callerUserId);
        var version = requireVersion(templateId, versionId);
        return QueryTemplateVersionMapper.toView(version, resolveAuthorName(version.getAuthorId()));
    }

    @Override
    public void recordSnapshot(QueryTemplateEntity template, UUID authorId,
                               QueryTemplateChangeType changeType) {
        var latest = versionRepository.findTopByTemplateIdOrderByVersionNumberDesc(template.getId());
        if (changeType == QueryTemplateChangeType.UPDATED
                && latest.isPresent() && isUnchanged(latest.get(), template)) {
            return;
        }
        int nextNumber = latest.map(v -> v.getVersionNumber() + 1).orElse(1);
        var version = new QueryTemplateVersionEntity();
        version.setId(UUID.randomUUID());
        version.setTemplateId(template.getId());
        version.setOrganizationId(template.getOrganizationId());
        version.setVersionNumber(nextNumber);
        version.setDatasourceId(template.getDatasourceId());
        version.setName(template.getName());
        version.setBody(template.getBody());
        version.setDescription(template.getDescription());
        version.setTags(template.getTags() == null ? new String[0] : template.getTags().clone());
        version.setVisibility(template.getVisibility());
        version.setChangeType(changeType);
        version.setAuthorId(authorId);
        version.setCreatedAt(Instant.now());
        versionRepository.save(version);
    }

    @Override
    public QueryTemplateVersionEntity requireVersion(UUID templateId, UUID versionId) {
        return versionRepository.findByTemplateIdAndId(templateId, versionId)
                .orElseThrow(() -> new QueryTemplateVersionNotFoundException(templateId, versionId));
    }

    // Mirrors DefaultQueryTemplateService.loadVisibleOrThrow — access control always uses the
    // current parent template, never a snapshot's point-in-time visibility.
    private void requireVisibleTemplate(UUID templateId, UUID organizationId, UUID callerUserId) {
        var template = templateRepository.findById(templateId)
                .orElseThrow(() -> new QueryTemplateNotFoundException(templateId));
        if (!template.getOrganizationId().equals(organizationId)) {
            throw new QueryTemplateNotFoundException(templateId);
        }
        if (template.getVisibility() == QueryTemplateVisibility.PRIVATE
                && !template.getOwnerId().equals(callerUserId)) {
            throw new QueryTemplateNotFoundException(templateId);
        }
    }

    private static boolean isUnchanged(QueryTemplateVersionEntity latest, QueryTemplateEntity template) {
        return Objects.equals(latest.getName(), template.getName())
                && Objects.equals(latest.getBody(), template.getBody())
                && Objects.equals(latest.getDescription(), template.getDescription())
                && Objects.equals(latest.getVisibility(), template.getVisibility())
                && Objects.equals(latest.getDatasourceId(), template.getDatasourceId())
                && Arrays.equals(normalize(latest.getTags()), normalize(template.getTags()));
    }

    private static String[] normalize(String[] tags) {
        return tags == null ? new String[0] : tags;
    }

    private Map<UUID, String> resolveAuthorNames(List<QueryTemplateVersionEntity> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        return entities.stream()
                .map(QueryTemplateVersionEntity::getAuthorId)
                .distinct()
                .map(userQueryService::findById)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(UserView::id, UserView::displayName, (a, b) -> a));
    }

    private String resolveAuthorName(UUID authorId) {
        return userQueryService.findById(authorId)
                .map(UserView::displayName)
                .orElse(null);
    }

    // version_number ordering is fixed by the repository method name; only page/size flow through,
    // so an arbitrary client sort= param can never reach JPA.
    private static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size());
    }
}
