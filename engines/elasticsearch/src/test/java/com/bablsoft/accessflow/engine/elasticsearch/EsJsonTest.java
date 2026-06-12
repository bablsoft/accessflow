package com.bablsoft.accessflow.engine.elasticsearch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsJsonTest {

    @Test
    void parsesValidJson() {
        var node = EsJson.parse("{\"a\":1,\"b\":[true,\"x\"]}");
        assertThat(node.isObject()).isTrue();
        assertThat(node.get("a").intValue()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> EsJson.parse("{not json"))
                .isInstanceOf(EsJson.JsonException.class);
    }

    @Test
    void boolFilterWrapsUserQueryInMustAndFiltersInFilter() {
        var user = EsJson.matchAll();
        var filters = List.<tools.jackson.databind.JsonNode>of(EsJson.term("tenant", "acme"));
        var wrapped = EsJson.boolFilter(user, filters);
        assertThat(EsJson.write(wrapped)).isEqualTo(
                "{\"bool\":{\"must\":[{\"match_all\":{}}],\"filter\":[{\"term\":{\"tenant\":\"acme\"}}]}}");
    }

    @Test
    void buildsTermRangeTermsAndNegations() {
        assertThat(EsJson.write(EsJson.term("f", 5))).isEqualTo("{\"term\":{\"f\":5}}");
        assertThat(EsJson.write(EsJson.range("age", "gte", 18)))
                .isEqualTo("{\"range\":{\"age\":{\"gte\":18}}}");
        assertThat(EsJson.write(EsJson.terms("g", List.of("a", "b"))))
                .isEqualTo("{\"terms\":{\"g\":[\"a\",\"b\"]}}");
        assertThat(EsJson.write(EsJson.not(EsJson.term("f", "v"))))
                .isEqualTo("{\"bool\":{\"must_not\":{\"term\":{\"f\":\"v\"}}}}");
        assertThat(EsJson.write(EsJson.notMatchAll()))
                .isEqualTo("{\"bool\":{\"must_not\":{\"match_all\":{}}}}");
        assertThat(EsJson.write(EsJson.idsQuery(List.of("1", "2"))))
                .isEqualTo("{\"ids\":{\"values\":[\"1\",\"2\"]}}");
    }

    @Test
    void forbidsScriptingAndClusterApisAnywhereInTheTree() {
        assertThatThrownBy(() -> EsJson.assertNoForbiddenConstructs(
                EsJson.parse("{\"query\":{\"script\":{\"source\":\"1\"}}}")))
                .isInstanceOf(EsParseException.class)
                .extracting(e -> ((EsParseException) e).messageKey())
                .isEqualTo("error.elasticsearch.forbidden_construct");
        assertThatThrownBy(() -> EsJson.assertNoForbiddenConstructs(
                EsJson.parse("{\"query\":{\"bool\":{\"must\":[{\"script_fields\":{}}]}}}")))
                .isInstanceOf(EsParseException.class);
        assertThatThrownBy(() -> EsJson.assertNoForbiddenConstructs(
                EsJson.parse("{\"runtime_mappings\":{\"x\":{}}}")))
                .isInstanceOf(EsParseException.class);
    }

    @Test
    void allowsSourceFieldSelectionWhichIsNotForbidden() {
        EsJson.assertNoForbiddenConstructs(EsJson.parse("{\"query\":{\"match_all\":{}},\"_source\":[\"a\"]}"));
    }

    @Test
    void convertsScalarsAndContainersToJavaValues() {
        assertThat(EsJson.toJava(EsJson.parse("\"x\""))).isEqualTo("x");
        assertThat(EsJson.toJava(EsJson.parse("7"))).isEqualTo(7);
        assertThat(EsJson.toJava(EsJson.parse("true"))).isEqualTo(true);
        assertThat(EsJson.toJava(EsJson.parse("{\"a\":1}"))).isEqualTo(Map.of("a", 1));
        assertThat(EsJson.toJava(EsJson.parse("[1,2]"))).isEqualTo(List.of(1, 2));
        assertThat(EsJson.toJava(EsJson.parse("null"))).isNull();
    }

    @Test
    void infersEsTypeNames() {
        assertThat(EsJson.esTypeName(EsJson.parse("\"x\""))).isEqualTo("text");
        assertThat(EsJson.esTypeName(EsJson.parse("3"))).isEqualTo("long");
        assertThat(EsJson.esTypeName(EsJson.parse("3.5"))).isEqualTo("double");
        assertThat(EsJson.esTypeName(EsJson.parse("true"))).isEqualTo("boolean");
        assertThat(EsJson.esTypeName(EsJson.parse("{}"))).isEqualTo("object");
        assertThat(EsJson.esTypeName(EsJson.parse("[]"))).isEqualTo("nested");
    }
}
