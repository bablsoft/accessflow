package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSchemaCatalogCodecTest {

    private final ApiSchemaCatalogCodec codec = new ApiSchemaCatalogCodec(JsonMapper.builder().build());

    @Test
    void operationsRoundTrip() {
        var ops = List.of(new ApiOperation("listPets", "GET", "/pets", null, false, null, null));

        assertThat(codec.readOperations(codec.writeOperations(ops))).isEqualTo(ops);
    }

    @Test
    void blankOrNullOperationsReadAsEmpty() {
        assertThat(codec.readOperations(null)).isEmpty();
        assertThat(codec.readOperations(" ")).isEmpty();
    }

    @Test
    void anEmptyFilterIsStoredAsNullAndReadBackAsEmpty() {
        assertThat(codec.writeFilter(OperationFilter.EMPTY)).isNull();
        assertThat(codec.writeFilter(null)).isNull();
        var entity = new ApiSchemaEntity();
        assertThat(codec.readFilter(entity).isEmpty()).isTrue();
        entity.setOperationFilter(" ");
        assertThat(codec.readFilter(entity).isEmpty()).isTrue();
    }

    @Test
    void aFilterRoundTrips() {
        var filter = new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false);
        var entity = new ApiSchemaEntity();
        entity.setOperationFilter(codec.writeFilter(filter));

        assertThat(codec.readFilter(entity).excludePaths()).containsExactly("/internal/**");
    }

    @Test
    void toViewCountsTheFullCatalogAndCopiesTheRefreshColumns() {
        var entity = new ApiSchemaEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(UUID.randomUUID());
        entity.setSchemaType(ApiSchemaType.OPENAPI);
        entity.setSourceUrl("https://example.test/spec");
        entity.setParsedOperations(codec.writeOperations(List.of(
                new ApiOperation("a", "GET", "/a", null, false, null, null),
                new ApiOperation("b", "GET", "/b", null, false, null, null))));
        entity.setOperationCount(1);
        entity.setRefreshIntervalMinutes(15);

        var view = codec.toView(entity);

        assertThat(view.totalOperationCount()).isEqualTo(2);
        assertThat(view.operationCount()).isEqualTo(1);
        assertThat(view.refreshIntervalMinutes()).isEqualTo(15);
        assertThat(view.sourceUrl()).isEqualTo("https://example.test/spec");
    }

    @Test
    void toViewOfABlankCatalogCountsZero() {
        var entity = new ApiSchemaEntity();
        entity.setSchemaType(ApiSchemaType.OPENAPI);
        entity.setParsedOperations(" ");

        assertThat(codec.toView(entity).totalOperationCount()).isZero();
    }
}
