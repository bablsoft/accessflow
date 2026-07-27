package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.discovery.api.DiscoveryDecision;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingService;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingView;
import com.bablsoft.accessflow.discovery.api.DiscoveryRowStatus;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultDiscoveryFindingService implements DiscoveryFindingService {

    private final DiscoveryFindingRepository findingRepository;
    private final DiscoveryFindingStateService stateService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DiscoveryFindingView> find(UUID datasourceId, UUID organizationId,
                                                   DiscoveryFindingStatus status,
                                                   PageRequest page) {
        var pageable = DiscoveryPageAdapter.toSpringPageable(page);
        if (pageable.isPaged() && pageable.getSort().isUnsorted()) {
            pageable = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(),
                    pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "lastDetectedAt"));
        }
        var result = status == null
                ? findingRepository.findAllByDatasourceIdAndOrganizationId(datasourceId,
                        organizationId, pageable)
                : findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(datasourceId,
                        organizationId, status, pageable);
        return DiscoveryPageAdapter.toPageResponse(result.map(DiscoveryViewMapper::toView));
    }

    @Override
    public BulkDecisionOutcome decide(UUID datasourceId, UUID organizationId, UUID actorId,
                                      List<UUID> findingIds, DiscoveryDecision decision) {
        // Intentionally NOT @Transactional: each row is decided by DiscoveryFindingStateService in
        // its own transaction, so one bad row never rolls back a successful peer.
        var rows = new ArrayList<BulkDecisionOutcome.Row>(findingIds.size());
        for (var findingId : findingIds) {
            rows.add(decideOne(datasourceId, organizationId, actorId, findingId, decision));
        }
        return new BulkDecisionOutcome(List.copyOf(rows));
    }

    private BulkDecisionOutcome.Row decideOne(UUID datasourceId, UUID organizationId, UUID actorId,
                                              UUID findingId, DiscoveryDecision decision) {
        var finding = findingRepository.findByIdAndDatasourceIdAndOrganizationId(findingId,
                datasourceId, organizationId).orElse(null);
        if (finding == null) {
            return new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.NOT_FOUND, null, null);
        }
        if (finding.getStatus() != DiscoveryFindingStatus.PENDING) {
            return new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.INVALID_STATE,
                    finding.getStatus(), DiscoveryViewMapper.toView(finding));
        }
        try {
            if (decision == DiscoveryDecision.DISMISS) {
                var dismissed = stateService.dismiss(finding, actorId);
                return new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.SUCCESS,
                        DiscoveryFindingStatus.DISMISSED, DiscoveryViewMapper.toView(dismissed));
            }
            var outcome = stateService.confirm(finding, actorId);
            return new BulkDecisionOutcome.Row(findingId,
                    outcome.tagConflict() ? DiscoveryRowStatus.TAG_CONFLICT
                            : DiscoveryRowStatus.SUCCESS,
                    DiscoveryFindingStatus.CONFIRMED,
                    DiscoveryViewMapper.toView(outcome.finding()));
        } catch (RuntimeException ex) {
            log.error("Discovery finding decision failed for finding {}", findingId, ex);
            return new BulkDecisionOutcome.Row(findingId, DiscoveryRowStatus.ERROR,
                    DiscoveryFindingStatus.PENDING, DiscoveryViewMapper.toView(finding));
        }
    }
}
