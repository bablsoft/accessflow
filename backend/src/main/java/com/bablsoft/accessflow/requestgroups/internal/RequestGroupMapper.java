package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps request-group entities to their public {@code api} read models, enriching names from lookups. */
final class RequestGroupMapper {

    private RequestGroupMapper() {
    }

    static RequestGroupView toView(RequestGroupEntity group, List<RequestGroupItemEntity> items,
                                   UserView submitter, Map<UUID, DatasourceRef> datasources,
                                   Map<UUID, ApiConnectorView> connectors) {
        var itemViews = items.stream()
                .map(i -> toItemView(i, datasources, connectors))
                .toList();
        return new RequestGroupView(
                group.getId(),
                group.getOrganizationId(),
                group.getSubmittedBy(),
                submitter == null ? null : submitter.displayName(),
                group.getName(),
                group.getDescription(),
                group.getStatus(),
                group.isContinueOnError(),
                group.getScheduledFor(),
                group.getAiRiskLevel(),
                group.getAiRiskScore(),
                group.getRequiredApprovals(),
                group.getCurrentReviewStage(),
                group.getErrorMessage(),
                group.getExecutionStartedAt(),
                group.getExecutionCompletedAt(),
                group.getCreatedAt(),
                group.getUpdatedAt(),
                itemViews);
    }

    static RequestGroupItemView toItemView(RequestGroupItemEntity i,
                                           Map<UUID, DatasourceRef> datasources,
                                           Map<UUID, ApiConnectorView> connectors) {
        var dsName = i.getDatasourceId() == null ? null
                : datasources.getOrDefault(i.getDatasourceId(), new DatasourceRef(i.getDatasourceId(), null)).name();
        var connName = i.getApiConnectorId() == null ? null
                : (connectors.containsKey(i.getApiConnectorId())
                        ? connectors.get(i.getApiConnectorId()).name() : null);
        return new RequestGroupItemView(
                i.getId(),
                i.getSequenceOrder(),
                i.getTargetKind(),
                i.getDatasourceId(),
                dsName,
                i.getSqlText(),
                i.getQueryType(),
                i.isTransactional(),
                i.getApiConnectorId(),
                connName,
                i.getOperationId(),
                i.getVerb(),
                i.getRequestPath(),
                i.getAiAnalysisId(),
                i.getAiRiskLevel(),
                i.getAiRiskScore(),
                i.getStatus(),
                i.getResponseStatusCode(),
                i.getRowsAffected(),
                i.getErrorMessage(),
                i.getDurationMs(),
                i.getExecutedAt());
    }
}
