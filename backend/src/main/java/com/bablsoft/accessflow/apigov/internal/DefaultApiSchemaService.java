package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaParserRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiSchemaService implements ApiSchemaService {

    private static final TypeReference<List<ApiOperation>> OPS_TYPE = new TypeReference<>() {
    };

    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaRepository schemaRepository;
    private final SchemaParserRegistry parserRegistry;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ApiSchemaView upload(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                String rawContent, String sourceUrl) {
        requireConnector(connectorId, organizationId);
        var operations = parserRegistry.parse(schemaType, rawContent);
        var entity = new ApiSchemaEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connectorId);
        entity.setSchemaType(schemaType);
        entity.setRawContent(rawContent);
        entity.setSourceUrl(sourceUrl);
        entity.setParsedOperations(objectMapper.writeValueAsString(operations));
        entity.setOperationCount(operations.size());
        return toView(schemaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiSchemaView> list(UUID connectorId, UUID organizationId) {
        requireConnector(connectorId, organizationId);
        return schemaRepository.findByConnectorIdOrderByCreatedAtDesc(connectorId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public void delete(UUID connectorId, UUID organizationId, UUID schemaId) {
        requireConnector(connectorId, organizationId);
        var entity = schemaRepository.findByIdAndConnectorId(schemaId, connectorId)
                .orElseThrow(() -> new ApiSchemaNotFoundException(schemaId));
        schemaRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiOperation> listOperations(UUID connectorId, UUID organizationId) {
        requireConnector(connectorId, organizationId);
        return schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connectorId)
                .map(s -> objectMapper.<List<ApiOperation>>readValue(s.getParsedOperations(), OPS_TYPE))
                .orElseGet(List::of);
    }

    private void requireConnector(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private ApiSchemaView toView(ApiSchemaEntity e) {
        return new ApiSchemaView(e.getId(), e.getConnectorId(), e.getSchemaType(), e.getSourceUrl(),
                e.getOperationCount(), e.getCreatedAt());
    }
}
