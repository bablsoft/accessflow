package com.bablsoft.accessflow.apigov.internal.schema;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonShapeInferrerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonShapeInferrer inferrer = new JsonShapeInferrer(objectMapper);

    @Test
    void infersScalarTypesFromAnObjectExample() {
        var shape = inferrer.inferFromJson("""
                { "name": "ada", "age": 36, "score": 9.5, "active": true, "note": null }""");

        assertThat(shape).isEqualTo("""
                {"type":"object","properties":{\
                "name":{"type":"string"},\
                "age":{"type":"integer"},\
                "score":{"type":"number"},\
                "active":{"type":"boolean"},\
                "note":{"type":"null"}}}""");
    }

    @Test
    void infersNestedObjects() {
        var shape = inferrer.inferFromJson("{ \"user\": { \"email\": \"a@b.c\" } }");

        assertThat(shape).isEqualTo("""
                {"type":"object","properties":{"user":{"type":"object","properties":{\
                "email":{"type":"string"}}}}}""");
    }

    @Test
    void infersArrayElementTypeFromTheFirstElement() {
        var shape = inferrer.inferFromJson("{ \"tags\": [\"a\", \"b\"] }");

        assertThat(shape).isEqualTo("""
                {"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}}}""");
    }

    @Test
    void leavesAnEmptyArrayWithoutAnElementType() {
        var shape = inferrer.inferFromJson("{ \"tags\": [] }");

        assertThat(shape).isEqualTo("""
                {"type":"object","properties":{"tags":{"type":"array","items":{}}}}""");
    }

    @Test
    void infersATopLevelArray() {
        var shape = inferrer.inferFromJson("[{ \"id\": 1 }]");

        assertThat(shape).isEqualTo("""
                {"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"}}}}""");
    }

    @Test
    void infersATopLevelScalar() {
        assertThat(inferrer.inferFromJson("\"plain\"")).isEqualTo("{\"type\":\"string\"}");
    }

    @Test
    void stopsDescendingAtTheDepthBound() {
        // 20 levels of nesting; the inferrer collapses everything past level 12 to a bare object.
        var deep = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        deep.append("}".repeat(20));

        var shape = inferrer.inferFromJson(deep.toString());

        assertThat(shape).endsWith("{\"type\":\"object\"}" + "}}".repeat(11) + "}}");
        assertThat(shape).doesNotContain("\"integer\"");
    }

    @Test
    void returnsNullForAbsentBlankOrNonJsonInput() {
        assertThat(inferrer.inferFromJson(null)).isNull();
        assertThat(inferrer.inferFromJson("   ")).isNull();
        assertThat(inferrer.inferFromJson("<xml/>")).isNull();
        assertThat(inferrer.inferFromJson("{ unclosed")).isNull();
    }

    @Test
    void infersFlatFieldsAsStringProperties() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("grant_type", "client_credentials");
        fields.put("scope", "read");

        assertThat(inferrer.inferFromFields(fields)).isEqualTo("""
                {"type":"object","properties":{\
                "grant_type":{"type":"string"},"scope":{"type":"string"}}}""");
    }

    @Test
    void returnsNullForAbsentOrEmptyFields() {
        assertThat(inferrer.inferFromFields(null)).isNull();
        assertThat(inferrer.inferFromFields(Map.of())).isNull();
    }
}
