package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaParserRegistryTest {

    @Test
    void dispatchesToTheParserForTheType() {
        var registry = new SchemaParserRegistry(List.of(new GraphQlSchemaParser(), new ProtoSchemaParser()));

        var parsed = registry.parse(ApiSchemaType.GRAPHQL_SDL, "type Query { ping: String }");

        assertThat(parsed.operations()).extracting("operationId").containsExactly("ping");
        assertThat(parsed.detectedAuthMethod()).isNull();
        assertThat(parsed.sanitizedContent()).isNull();
    }

    @Test
    void dispatchesToThePostmanParserAndCarriesItsAuthAndSanitizedContent() {
        var registry = new SchemaParserRegistry(
                List.of(new GraphQlSchemaParser(), new PostmanCollectionParser(new ObjectMapper())));

        var parsed = registry.parse(ApiSchemaType.POSTMAN_COLLECTION, """
                { "info": { "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
                  "auth": { "type": "basic", "basic": [{ "key": "password", "value": "hunter2" }] },
                  "item": [{ "name": "Ping", "request": { "method": "GET", "url": { "path": ["ping"] } } }] }""");

        assertThat(parsed.operations()).extracting("operationId").containsExactly("ping");
        assertThat(parsed.detectedAuthMethod()).isEqualTo(ApiAuthMethod.BASIC);
        assertThat(parsed.sanitizedContent()).doesNotContain("hunter2");
    }

    @Test
    void throwsWhenNoParserRegistered() {
        var registry = new SchemaParserRegistry(List.of(new ProtoSchemaParser()));

        assertThatThrownBy(() -> registry.parse(ApiSchemaType.OPENAPI, "x"))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("No parser registered");
    }
}
