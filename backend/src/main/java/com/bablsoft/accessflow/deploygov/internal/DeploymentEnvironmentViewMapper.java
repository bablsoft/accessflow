package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;

import java.util.List;

/**
 * The one entity → {@link DeploymentEnvironmentView} mapping, shared by the admin service and both
 * lookup services so a new column (#877's {@code datasourceId}) cannot land in one view and drift
 * out of another.
 */
final class DeploymentEnvironmentViewMapper {

    private DeploymentEnvironmentViewMapper() {
    }

    static DeploymentEnvironmentView toView(DeploymentEnvironmentEntity e) {
        return new DeploymentEnvironmentView(
                e.getId(), e.getPipelineId(), e.getName(), e.getSortOrder(), e.isRequireReview(),
                e.getRequiredApprovals(), e.getReviewPlanId(), e.isAllowBreakGlass(), e.getCreatedAt(),
                e.getTags() == null ? List.of() : List.of(e.getTags()),
                e.getDatasourceId());
    }
}
