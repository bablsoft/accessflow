package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Reads and writes the JSON columns of {@code api_schemas} and maps a row to {@link ApiSchemaView}. */
@Component
@RequiredArgsConstructor
class ApiSchemaCatalogCodec {

    private static final TypeReference<List<ApiOperation>> OPS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    List<ApiOperation> readOperations(String raw) {
        return raw == null || raw.isBlank() ? List.of() : objectMapper.readValue(raw, OPS_TYPE);
    }

    String writeOperations(List<ApiOperation> operations) {
        return objectMapper.writeValueAsString(operations);
    }

    OperationFilter readFilter(ApiSchemaEntity entity) {
        var raw = entity.getOperationFilter();
        if (raw == null || raw.isBlank()) {
            return OperationFilter.EMPTY;
        }
        return objectMapper.readValue(raw, OperationFilter.class);
    }

    String writeFilter(OperationFilter filter) {
        return filter == null || filter.isEmpty() ? null : objectMapper.writeValueAsString(filter);
    }

    ApiSchemaView toView(ApiSchemaEntity e) {
        return new ApiSchemaView(e.getId(), e.getConnectorId(), e.getSchemaType(), e.getSourceUrl(),
                e.getOperationCount(), totalOperationCount(e), readFilter(e), e.getDetectedAuthMethod(),
                e.getRefreshIntervalMinutes(), e.getCurrentRevisionId(), e.getLastRefreshAt(),
                e.getLastRefreshError(), e.getCreatedAt());
    }

    /** Array length only — avoids materializing every {@link ApiOperation} just to count them. */
    private int totalOperationCount(ApiSchemaEntity entity) {
        var raw = entity.getParsedOperations();
        return raw == null || raw.isBlank() ? 0 : objectMapper.readTree(raw).size();
    }
}
