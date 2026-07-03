package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps request-group entities to their public {@code api} read models, enriching names from lookups. */
final class RequestGroupMapper {

    private RequestGroupMapper() {
    }

    static RequestGroupView toView(RequestGroupEntity group, List<RequestGroupItemEntity> items,
                                   UserView submitter, Map<UUID, DatasourceRef> datasources,
                                   Map<UUID, ApiConnectorView> connectors,
                                   Map<UUID, QueryDetailView.AiAnalysisDetail> analysesByItemId,
                                   ObjectMapper objectMapper, boolean includeComposition) {
        var itemViews = items.stream()
                .map(i -> toItemView(i, datasources, connectors, analysesByItemId, objectMapper,
                        includeComposition))
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
                                           Map<UUID, ApiConnectorView> connectors,
                                           Map<UUID, QueryDetailView.AiAnalysisDetail> analysesByItemId,
                                           ObjectMapper objectMapper, boolean includeComposition) {
        var dsName = i.getDatasourceId() == null ? null
                : datasources.getOrDefault(i.getDatasourceId(), new DatasourceRef(i.getDatasourceId(), null)).name();
        var connName = i.getApiConnectorId() == null ? null
                : (connectors.containsKey(i.getApiConnectorId())
                        ? connectors.get(i.getApiConnectorId()).name() : null);
        var withComposition = includeComposition && i.getTargetKind() == RequestGroupTargetKind.API_CALL;
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
                withComposition ? readStringMap(objectMapper, i.getRequestHeaders()) : Map.of(),
                withComposition ? readStringMap(objectMapper, i.getQueryParams()) : Map.of(),
                withComposition ? i.getBodyType() : null,
                withComposition ? i.getRequestContentType() : null,
                withComposition ? i.getRequestBody() : null,
                withComposition ? readFormFields(objectMapper, i.getFormFields()) : List.of(),
                withComposition ? i.getBinaryFilename() : null,
                i.getAiAnalysisId(),
                i.getAiRiskLevel(),
                i.getAiRiskScore(),
                analysesByItemId.get(i.getId()),
                i.getStatus(),
                i.getResponseStatusCode(),
                i.getRowsAffected(),
                i.getErrorMessage(),
                i.getDurationMs(),
                i.getExecutedAt());
    }

    /** Fail-soft mirror of the service's {@code writeJson}: unreadable stored JSON yields empty. */
    private static Map<String, String> readStringMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static List<ApiFormField> readFormFields(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var stored = objectMapper.readValue(json,
                    new TypeReference<List<RequestGroupItemInput.ApiFormFieldInput>>() {
                    });
            return stored.stream()
                    .map(f -> new ApiFormField(f.name(),
                            f.file() ? ApiFormField.ApiFormFieldType.FILE
                                    : ApiFormField.ApiFormFieldType.TEXT,
                            f.value(), f.filename(), f.contentType()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
