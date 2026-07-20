package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.apigov.internal.schema.OperationFilterMatcher;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaParserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiSchemaServiceTest {

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiSchemaRepository schemaRepository;
    @Mock private SchemaParserRegistry parserRegistry;

    private DefaultApiSchemaService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiSchemaService(connectorRepository, schemaRepository, parserRegistry,
                new OperationFilterMatcher(), JsonMapper.builder().build());
    }

    private void connectorExists() {
        var entity = new ApiConnectorEntity();
        entity.setId(connectorId);
        entity.setOrganizationId(orgId);
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(entity));
    }

    @Test
    void uploadParsesAndPersistsCatalog() {
        connectorExists();
        when(parserRegistry.parse(eq(ApiSchemaType.OPENAPI), any())).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null),
                new ApiOperation("createPet", "POST", "/pets", null, true, null, null)));
        when(schemaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null,
                OperationFilter.EMPTY);

        assertThat(view.operationCount()).isEqualTo(2);
        assertThat(view.totalOperationCount()).isEqualTo(2);
        assertThat(view.operationFilter().isEmpty()).isTrue();
        assertThat(view.schemaType()).isEqualTo(ApiSchemaType.OPENAPI);
    }

    @Test
    void uploadAppliesFilterToOperationCountAndPersistsFullCatalog() {
        connectorExists();
        when(parserRegistry.parse(eq(ApiSchemaType.OPENAPI), any())).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null),
                new ApiOperation("internalSync", "POST", "/internal/sync", null, true, null, null)));
        var saved = new java.util.concurrent.atomic.AtomicReference<ApiSchemaEntity>();
        when(schemaRepository.save(any())).thenAnswer(i -> {
            saved.set(i.getArgument(0));
            return i.getArgument(0);
        });
        var filter = new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false);

        var view = service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null, filter);

        assertThat(view.operationCount()).isEqualTo(1);         // post-filter kept count
        assertThat(view.totalOperationCount()).isEqualTo(2);    // full catalog
        assertThat(view.operationFilter().excludePaths()).containsExactly("/internal/**");
        // parsed_operations keeps the complete catalog, operation_filter is persisted.
        assertThat(saved.get().getParsedOperations()).contains("internalSync");
        assertThat(saved.get().getOperationFilter()).contains("/internal/**");
    }

    @Test
    void uploadFetchesFromSourceUrlWhenRawContentBlank() throws Exception {
        connectorExists();
        when(parserRegistry.parse(eq(ApiSchemaType.OPENAPI), eq("fetched-spec")))
                .thenReturn(List.of(new ApiOperation("listPets", "GET", "/pets", null, false, null, null)));
        when(schemaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", exchange -> {
            byte[] body = "fetched-spec".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/spec";
            var view = service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, null, url,
                    OperationFilter.EMPTY);
            assertThat(view.operationCount()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadRejectsNonHttpSourceUrl() {
        connectorExists();
        assertThatThrownBy(() -> service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, null, "ftp://x/s",
                OperationFilter.EMPTY))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException.class);
    }

    @Test
    void uploadFetchFailsOnNon2xx() throws Exception {
        connectorExists();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/spec";
            assertThatThrownBy(() -> service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, null, url,
                    OperationFilter.EMPTY))
                    .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadRejectsUnknownConnector() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null,
                OperationFilter.EMPTY))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void listOperationsReadsCachedCatalogFromLatestSchema() {
        connectorExists();
        var schema = new ApiSchemaEntity();
        schema.setParsedOperations("[{\"operationId\":\"ping\",\"verb\":\"GET\",\"path\":\"/ping\","
                + "\"summary\":null,\"write\":false,\"requestSchema\":null,\"responseSchema\":null}]");
        when(schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connectorId))
                .thenReturn(Optional.of(schema));

        var ops = service.listOperations(connectorId, orgId);

        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).operationId()).isEqualTo("ping");
    }

    @Test
    void listOperationsAppliesStoredFilterOnRead() {
        connectorExists();
        var schema = new ApiSchemaEntity();
        schema.setParsedOperations("["
                + "{\"operationId\":\"ping\",\"verb\":\"GET\",\"path\":\"/ping\",\"write\":false},"
                + "{\"operationId\":\"internalSync\",\"verb\":\"POST\",\"path\":\"/internal/sync\",\"write\":true}]");
        schema.setOperationFilter("{\"excludePaths\":[\"/internal/**\"],\"excludeDeprecated\":false}");
        when(schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connectorId))
                .thenReturn(Optional.of(schema));

        var ops = service.listOperations(connectorId, orgId);

        assertThat(ops).extracting(ApiOperation::operationId).containsExactly("ping");
    }

    @Test
    void updateFilterRecomputesCountFromStoredCatalog() {
        connectorExists();
        var schemaId = UUID.randomUUID();
        var schema = new ApiSchemaEntity();
        schema.setId(schemaId);
        schema.setConnectorId(connectorId);
        schema.setSchemaType(ApiSchemaType.OPENAPI);
        schema.setParsedOperations("["
                + "{\"operationId\":\"ping\",\"verb\":\"GET\",\"path\":\"/ping\",\"write\":false},"
                + "{\"operationId\":\"internalSync\",\"verb\":\"POST\",\"path\":\"/internal/sync\",\"write\":true}]");
        when(schemaRepository.findByIdAndConnectorId(schemaId, connectorId)).thenReturn(Optional.of(schema));
        when(schemaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var filter = new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false);

        var view = service.updateFilter(connectorId, orgId, schemaId, filter);

        assertThat(view.operationCount()).isEqualTo(1);
        assertThat(view.totalOperationCount()).isEqualTo(2);
        assertThat(schema.getOperationFilter()).contains("/internal/**");
    }

    @Test
    void updateFilterRejectsUnknownSchema() {
        connectorExists();
        var schemaId = UUID.randomUUID();
        when(schemaRepository.findByIdAndConnectorId(schemaId, connectorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateFilter(connectorId, orgId, schemaId, OperationFilter.EMPTY))
                .isInstanceOf(ApiSchemaNotFoundException.class);
    }

    @Test
    void previewFilterReportsCountsWithoutPersisting() {
        connectorExists();
        when(parserRegistry.parse(eq(ApiSchemaType.OPENAPI), any())).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null),
                new ApiOperation("internalSync", "POST", "/internal/sync", null, true, null, null)));
        var filter = new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false);

        var preview = service.previewFilter(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null, filter);

        assertThat(preview.totalCount()).isEqualTo(2);
        assertThat(preview.keptCount()).isEqualTo(1);
        assertThat(preview.excluded()).extracting(ApiOperation::operationId).containsExactly("internalSync");
        org.mockito.Mockito.verify(schemaRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void listOperationsEmptyWhenNoSchema() {
        connectorExists();
        when(schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connectorId))
                .thenReturn(Optional.empty());

        assertThat(service.listOperations(connectorId, orgId)).isEmpty();
    }

    @Test
    void deleteRejectsUnknownSchema() {
        connectorExists();
        var schemaId = UUID.randomUUID();
        when(schemaRepository.findByIdAndConnectorId(schemaId, connectorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaNotFoundException.class);
    }

    @Test
    void deleteRemovesSchema() {
        connectorExists();
        var schemaId = UUID.randomUUID();
        var schema = new ApiSchemaEntity();
        schema.setId(schemaId);
        schema.setConnectorId(connectorId);
        when(schemaRepository.findByIdAndConnectorId(schemaId, connectorId)).thenReturn(Optional.of(schema));

        service.delete(connectorId, orgId, schemaId);

        verify(schemaRepository).delete(schema);
    }
}
