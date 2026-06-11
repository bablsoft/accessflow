package com.bablsoft.accessflow.engine.couchbase;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseJsonTest {

    private static Object parse(String json) {
        return CouchbaseJson.parseRow(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesObjectsPreservingOrderAndNesting() {
        var parsed = parse("{\"b\": 1, \"a\": {\"nested\": [1, 2]}}");
        assertThat(parsed).isInstanceOf(Map.class);
        var map = (Map<String, Object>) parsed;
        assertThat(map.keySet()).containsExactly("b", "a");
        assertThat(((Map<?, ?>) map.get("a")).get("nested")).isEqualTo(List.of(1, 2));
    }

    @Test
    void parsesScalars() {
        assertThat(parse("\"text\"")).isEqualTo("text");
        assertThat(parse("true")).isEqualTo(true);
        assertThat(parse("null")).isNull();
        assertThat(parse("[1, \"x\"]")).isEqualTo(List.of(1, "x"));
    }

    @Test
    void narrowsIntegralNumbersAndKeepsDecimalPrecision() {
        assertThat(parse("42")).isEqualTo(42);
        assertThat(parse("4200000000")).isEqualTo(4_200_000_000L);
        assertThat(parse("1.5")).isEqualTo(new BigDecimal("1.5"));
    }
}
