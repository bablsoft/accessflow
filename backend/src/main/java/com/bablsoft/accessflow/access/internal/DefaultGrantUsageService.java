package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageSummaryRepository;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Read side of least-privilege intelligence (#625). Serves the materialised summary; all writing is
 * {@code DefaultGrantUsageAggregationService}'s job.
 */
@Service
@RequiredArgsConstructor
class DefaultGrantUsageService implements GrantUsageService {

    /**
     * Worst first: never-used grants (null {@code lastUsedAt}) ahead of merely idle ones, then
     * longest-idle. {@code id} breaks ties so paging is stable — without it two rows with identical
     * timestamps can swap between pages and one is silently skipped.
     */
    private static final Sort DEFAULT_SORT = Sort.by(
            new Sort.Order(Sort.Direction.ASC, "lastUsedAt", Sort.NullHandling.NULLS_FIRST),
            Sort.Order.asc("id"));

    private final GrantUsageSummaryRepository summaryRepository;
    private final GrantUsageViewMapper viewMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<GrantUsageView> findFor(UUID organizationId, GrantResourceKind resourceKind,
                                            UUID resourceId, UUID userId) {
        if (organizationId == null || resourceKind == null || resourceId == null || userId == null) {
            return Optional.empty();
        }
        return summaryRepository
                .findByOrganizationIdAndResourceKindAndResourceIdAndUserId(
                        organizationId, resourceKind, resourceId, userId)
                .map(viewMapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<GrantUsageView> report(UUID organizationId, GrantUsageReportQuery query,
                                               PageRequest pageRequest) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        var filter = query == null ? GrantUsageReportQuery.empty() : query;
        var pageable = AccessPageAdapter.toSpringPageable(pageRequest);
        if (pageable.isPaged() && pageable.getSort().isUnsorted()) {
            pageable = org.springframework.data.domain.PageRequest.of(
                    pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
        }
        var page = summaryRepository.findAll(
                GrantUsageSpecifications.report(organizationId, filter), pageable);
        return AccessPageAdapter.toPageResponse(page.map(viewMapper::toView));
    }
}
