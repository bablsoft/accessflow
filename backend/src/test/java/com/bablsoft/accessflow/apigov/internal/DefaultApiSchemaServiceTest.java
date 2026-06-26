package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
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
                JsonMapper.builder().build());
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

        var view = service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null);

        assertThat(view.operationCount()).isEqualTo(2);
        assertThat(view.schemaType()).isEqualTo(ApiSchemaType.OPENAPI);
    }

    @Test
    void uploadRejectsUnknownConnector() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(connectorId, orgId, ApiSchemaType.OPENAPI, "spec", null))
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
