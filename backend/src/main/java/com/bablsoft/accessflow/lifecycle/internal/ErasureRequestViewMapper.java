package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Enriches a {@link DeletionRequestEntity} with the datasource name and requester email. */
@Component
@RequiredArgsConstructor
class ErasureRequestViewMapper {

    private final DatasourceLookupService datasourceLookupService;
    private final UserQueryService userQueryService;

    ErasureRequestView toView(DeletionRequestEntity entity) {
        var datasourceName = datasourceLookupService.findRef(entity.getDatasourceId())
                .map(DatasourceRef::name).orElse(null);
        var email = userQueryService.findById(entity.getRequestedBy())
                .map(UserView::email).orElse(null);
        return new ErasureRequestView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getDatasourceId(),
                datasourceName,
                entity.getSubjectType(),
                entity.getSubjectIdentifier(),
                entity.getStatus(),
                entity.getReason(),
                entity.getRequestedBy(),
                email,
                entity.getAiScopeAnalysisId(),
                entity.getScopeSnapshot(),
                entity.getEstimatedRows(),
                entity.getAffectedRows(),
                entity.getExecutedAt(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
