package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaParserRegistryTest {

    @Test
    void dispatchesToTheParserForTheType() {
        var registry = new SchemaParserRegistry(List.of(new GraphQlSchemaParser(), new ProtoSchemaParser()));

        var ops = registry.parse(ApiSchemaType.GRAPHQL_SDL, "type Query { ping: String }");

        assertThat(ops).extracting("operationId").containsExactly("ping");
    }

    @Test
    void throwsWhenNoParserRegistered() {
        var registry = new SchemaParserRegistry(List.of(new ProtoSchemaParser()));

        assertThatThrownBy(() -> registry.parse(ApiSchemaType.OPENAPI, "x"))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("No parser registered");
    }
}
