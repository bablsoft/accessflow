package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReviewerEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultReviewerEligibilityService implements ReviewerEligibilityService {

    private final DatasourceReviewerRepository datasourceReviewerRepository;
    private final UserGroupMembershipRepository userGroupMembershipRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Set<UUID>> findEligibleReviewerIds(UUID datasourceId) {
        List<DatasourceReviewerEntity> assignments =
                datasourceReviewerRepository.findAllByDatasource_Id(datasourceId);
        if (assignments.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> userIds = new LinkedHashSet<>();
        Set<UUID> groupIds = new HashSet<>();
        for (var assignment : assignments) {
            if (assignment.getUser() != null) {
                userIds.add(assignment.getUser().getId());
            } else if (assignment.getGroup() != null) {
                groupIds.add(assignment.getGroup().getId());
            }
        }
        if (!groupIds.isEmpty()) {
            userIds.addAll(userGroupMembershipRepository.findUserIdsInGroups(List.copyOf(groupIds)));
        }
        return Optional.of(userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasDatasourceScopedReviewers(UUID datasourceId) {
        return datasourceReviewerRepository.existsByDatasource_Id(datasourceId);
    }
}
