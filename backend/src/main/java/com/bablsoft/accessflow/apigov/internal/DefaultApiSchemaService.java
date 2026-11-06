package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotRefreshableException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.api.OperationFilterPreview;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.apigov.internal.schema.OperationFilterMatcher;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaParserRegistry;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaSourceFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiSchemaService implements ApiSchemaService {

    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaRepository schemaRepository;
    private final SchemaParserRegistry parserRegistry;
    private final OperationFilterMatcher filterMatcher;
    private final SchemaSourceFetcher sourceFetcher;
    private final ApiSchemaCatalogCodec codec;

    @Override
    @Transactional
    public ApiSchemaView upload(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                String rawContent, String sourceUrl, OperationFilter filter,
                                Integer refreshIntervalMinutes) {
        requireConnector(connectorId, organizationId);
        var hasSourceUrl = sourceUrl != null && !sourceUrl.isBlank();
        if (refreshIntervalMinutes != null && !hasSourceUrl) {
            throw new ApiSchemaNotRefreshableException(null);
        }
        var content = (rawContent == null || rawContent.isBlank()) && hasSourceUrl
                ? sourceFetcher.fetch(sourceUrl) : rawContent;
        var parsed = parserRegistry.parse(schemaType, content);
        var operations = parsed.operations();
        var effectiveFilter = filter == null ? OperationFilter.EMPTY : filter;
        var kept = filterMatcher.apply(operations, effectiveFilter);
        var entity = new ApiSchemaEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connectorId);
        entity.setSchemaType(schemaType);
        // A parser may hand back a redacted document to persist in place of the upload — Postman
        // exports carry live credentials and pre-request/test scripts that must never be stored.
        entity.setRawContent(parsed.sanitizedContent() != null ? parsed.sanitizedContent() : content);
        entity.setDetectedAuthMethod(parsed.detectedAuthMethod());
        entity.setSourceUrl(sourceUrl);
        entity.setParsedOperations(codec.writeOperations(operations));
        entity.setOperationFilter(codec.writeFilter(effectiveFilter));
        entity.setOperationCount(kept.size());
        entity.setRefreshIntervalMinutes(refreshIntervalMinutes);
        return codec.toView(schemaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiSchemaView> list(UUID connectorId, UUID organizationId) {
        requireConnector(connectorId, organizationId);
        return schemaRepository.findByConnectorIdOrderByCreatedAtDesc(connectorId).stream()
                .map(codec::toView)
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
    @Transactional
    public ApiSchemaView updateFilter(UUID connectorId, UUID organizationId, UUID schemaId,
                                      OperationFilter filter) {
        requireConnector(connectorId, organizationId);
        var entity = schemaRepository.findByIdAndConnectorId(schemaId, connectorId)
                .orElseThrow(() -> new ApiSchemaNotFoundException(schemaId));
        var effectiveFilter = filter == null ? OperationFilter.EMPTY : filter;
        var operations = codec.readOperations(entity.getParsedOperations());
        entity.setOperationFilter(codec.writeFilter(effectiveFilter));
        entity.setOperationCount(filterMatcher.apply(operations, effectiveFilter).size());
        return codec.toView(schemaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public OperationFilterPreview previewFilter(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                                String rawContent, String sourceUrl, OperationFilter filter) {
        requireConnector(connectorId, organizationId);
        var content = (rawContent == null || rawContent.isBlank()) && sourceUrl != null && !sourceUrl.isBlank()
                ? sourceFetcher.fetch(sourceUrl) : rawContent;
        var operations = parserRegistry.parse(schemaType, content).operations();
        var effectiveFilter = filter == null ? OperationFilter.EMPTY : filter;
        var kept = filterMatcher.apply(operations, effectiveFilter);
        var excluded = operations.stream().filter(op -> !kept.contains(op)).toList();
        return new OperationFilterPreview(operations.size(), kept.size(), excluded);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiOperation> listOperations(UUID connectorId, UUID organizationId) {
        requireConnector(connectorId, organizationId);
        return schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connectorId)
                .map(s -> filterMatcher.apply(codec.readOperations(s.getParsedOperations()), codec.readFilter(s)))
                .orElseGet(List::of);
    }

    private void requireConnector(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }
}
