package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Enriches an {@link AccessGrantRequestEntity} with the requester email and datasource name. */
@Component
@RequiredArgsConstructor
class AccessRequestViewMapper {

    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;

    AccessRequestView toView(AccessGrantRequestEntity entity) {
        var email = userQueryService.findById(entity.getRequesterId())
                .map(UserView::email).orElse(null);
        var datasourceName = datasourceLookupService.findRef(entity.getDatasourceId())
                .map(DatasourceRef::name).orElse(null);
        return new AccessRequestView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getRequesterId(),
                email,
                entity.getDatasourceId(),
                datasourceName,
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                entity.getRequestedDuration(),
                entity.getJustification(),
                entity.isPreApproveQueries(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getGrantedPermissionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    static List<String> toList(String[] values) {
        return values == null ? null : List.of(values);
    }
}
