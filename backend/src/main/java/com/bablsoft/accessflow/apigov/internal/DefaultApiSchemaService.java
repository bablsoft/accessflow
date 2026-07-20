package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiSchemaService implements ApiSchemaService {

    private static final TypeReference<List<ApiOperation>> OPS_TYPE = new TypeReference<>() {
    };
    private static final long MAX_FETCH_BYTES = 5L * 1024 * 1024;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaRepository schemaRepository;
    private final SchemaParserRegistry parserRegistry;
    private final OperationFilterMatcher filterMatcher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ApiSchemaView upload(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                String rawContent, String sourceUrl, OperationFilter filter) {
        requireConnector(connectorId, organizationId);
        var content = (rawContent == null || rawContent.isBlank()) && sourceUrl != null && !sourceUrl.isBlank()
                ? fetch(sourceUrl) : rawContent;
        var operations = parserRegistry.parse(schemaType, content);
        var effectiveFilter = filter == null ? OperationFilter.EMPTY : filter;
        var kept = filterMatcher.apply(operations, effectiveFilter);
        var entity = new ApiSchemaEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connectorId);
        entity.setSchemaType(schemaType);
        entity.setRawContent(content);
        entity.setSourceUrl(sourceUrl);
        entity.setParsedOperations(objectMapper.writeValueAsString(operations));
        entity.setOperationFilter(effectiveFilter.isEmpty() ? null : objectMapper.writeValueAsString(effectiveFilter));
        entity.setOperationCount(kept.size());
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
    @Transactional
    public ApiSchemaView updateFilter(UUID connectorId, UUID organizationId, UUID schemaId,
                                      OperationFilter filter) {
        requireConnector(connectorId, organizationId);
        var entity = schemaRepository.findByIdAndConnectorId(schemaId, connectorId)
                .orElseThrow(() -> new ApiSchemaNotFoundException(schemaId));
        var effectiveFilter = filter == null ? OperationFilter.EMPTY : filter;
        var operations = parsedOperations(entity);
        entity.setOperationFilter(effectiveFilter.isEmpty() ? null : objectMapper.writeValueAsString(effectiveFilter));
        entity.setOperationCount(filterMatcher.apply(operations, effectiveFilter).size());
        return toView(schemaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public OperationFilterPreview previewFilter(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                                String rawContent, String sourceUrl, OperationFilter filter) {
        requireConnector(connectorId, organizationId);
        var content = (rawContent == null || rawContent.isBlank()) && sourceUrl != null && !sourceUrl.isBlank()
                ? fetch(sourceUrl) : rawContent;
        var operations = parserRegistry.parse(schemaType, content);
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
                .map(s -> filterMatcher.apply(parsedOperations(s), readFilter(s)))
                .orElseGet(List::of);
    }

    private List<ApiOperation> parsedOperations(ApiSchemaEntity entity) {
        return objectMapper.readValue(entity.getParsedOperations(), OPS_TYPE);
    }

    private OperationFilter readFilter(ApiSchemaEntity entity) {
        var raw = entity.getOperationFilter();
        if (raw == null || raw.isBlank()) {
            return OperationFilter.EMPTY;
        }
        return objectMapper.readValue(raw, OperationFilter.class);
    }

    private String fetch(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException ex) {
            throw new ApiSchemaFetchException("Invalid schema URL");
        }
        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ApiSchemaFetchException("Schema URL must be http(s)");
        }
        try (var client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build()) {
            var request = HttpRequest.newBuilder(uri).timeout(FETCH_TIMEOUT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new ApiSchemaFetchException("Schema URL returned HTTP " + response.statusCode());
            }
            byte[] body = response.body() != null ? response.body() : new byte[0];
            if (body.length > MAX_FETCH_BYTES) {
                throw new ApiSchemaFetchException("Fetched schema exceeds the maximum allowed size");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new ApiSchemaFetchException("Could not fetch schema: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiSchemaFetchException("Schema fetch interrupted");
        }
    }

    private void requireConnector(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private ApiSchemaView toView(ApiSchemaEntity e) {
        return new ApiSchemaView(e.getId(), e.getConnectorId(), e.getSchemaType(), e.getSourceUrl(),
                e.getOperationCount(), totalOperationCount(e), readFilter(e), e.getCreatedAt());
    }

    /** Array length only — avoids materializing every {@link ApiOperation} just to count them. */
    private int totalOperationCount(ApiSchemaEntity entity) {
        var raw = entity.getParsedOperations();
        return raw == null || raw.isBlank() ? 0 : objectMapper.readTree(raw).size();
    }
}
