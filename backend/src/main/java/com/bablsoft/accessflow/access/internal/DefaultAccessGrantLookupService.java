package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantDecisionRepository;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAccessGrantLookupService implements AccessGrantLookupService {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantDecisionRepository decisionRepository;
    private final UserQueryService userQueryService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<AccessGrantView> findActivePreApprovedGrants(UUID organizationId, UUID userId,
                                                             UUID datasourceId) {
        return requestRepository
                .findAllByOrganizationIdAndRequesterIdAndDatasourceIdAndStatusAndPreApproveQueriesTrueAndExpiresAtAfter(
                        organizationId, userId, datasourceId, AccessGrantStatus.APPROVED,
                        clock.instant())
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccessGrantView> findGrant(UUID accessGrantId) {
        return requestRepository.findById(accessGrantId).map(this::toView);
    }

    private AccessGrantView toView(AccessGrantRequestEntity entity) {
        // The final-stage APPROVED decision is the one that flipped the request to APPROVED —
        // with decisions ordered by decided_at ascending, that is the last APPROVED row.
        var approval = decisionRepository
                .findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(entity.getId())
                .stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVED)
                .reduce((first, second) -> second)
                .orElse(null);
        var approverEmail = approval == null ? null
                : userQueryService.findById(approval.getReviewerId())
                        .map(UserView::email).orElse(null);
        return new AccessGrantView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getRequesterId(),
                entity.getDatasourceId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                AccessRequestViewMapper.toList(entity.getAllowedSchemas()),
                AccessRequestViewMapper.toList(entity.getAllowedTables()),
                entity.getStatus(),
                entity.getExpiresAt(),
                approval == null ? null : approval.getReviewerId(),
                approverEmail,
                approval == null ? null : approval.getDecidedAt());
    }
}
