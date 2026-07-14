package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorRef;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enriches an {@link AccessGrantRequestEntity} with the requester email and the target resource
 * (datasource or API connector) name.
 */
@Component
@RequiredArgsConstructor
class AccessRequestViewMapper {

    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;
    private final ApiConnectorLookupService connectorLookupService;

    AccessRequestView toView(AccessGrantRequestEntity entity) {
        var email = userQueryService.findById(entity.getRequesterId())
                .map(UserView::email).orElse(null);
        var datasourceName = entity.getDatasourceId() == null ? null
                : datasourceLookupService.findRef(entity.getDatasourceId())
                        .map(DatasourceRef::name).orElse(null);
        var connectorName = entity.getConnectorId() == null ? null
                : connectorLookupService.findRef(entity.getConnectorId())
                        .map(ApiConnectorRef::name).orElse(null);
        return new AccessRequestView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getRequesterId(),
                email,
                resourceKind(entity),
                entity.getDatasourceId(),
                datasourceName,
                entity.getConnectorId(),
                connectorName,
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                toList(entity.getAllowedOperations()),
                entity.getRequestedDuration(),
                entity.getJustification(),
                entity.isPreApproveQueries(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getGrantedPermissionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    static AccessResourceKind resourceKind(AccessGrantRequestEntity entity) {
        return entity.isConnectorRequest() ? AccessResourceKind.API_CONNECTOR
                : AccessResourceKind.DATASOURCE;
    }

    static List<String> toList(String[] values) {
        return values == null ? null : List.of(values);
    }
}
