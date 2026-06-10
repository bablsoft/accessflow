package com.bablsoft.accessflow.proxy.internal.driver;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineConfigPropertiesTest {

    @Test
    void nullEnginesMapBindsAsEmpty() {
        var properties = new EngineConfigProperties(null);

        assertThat(properties.engines()).isEmpty();
        assertThat(properties.forEngine("mongodb")).isEmpty();
    }

    @Test
    void unknownEngineIdYieldsEmptyMap() {
        var properties = new EngineConfigProperties(
                Map.of("mongodb", Map.of("connect-timeout", "PT10S")));

        assertThat(properties.forEngine("redis")).isEmpty();
    }

    @Test
    void canonicalKebabKeysPassThroughUnchanged() {
        var properties = new EngineConfigProperties(Map.of("mongodb", Map.of(
                "connect-timeout", "PT3S",
                "server-selection-timeout", "PT7S",
                "max-pool-size", "25")));

        assertThat(properties.forEngine("mongodb"))
                .containsEntry("connect-timeout", "PT3S")
                .containsEntry("server-selection-timeout", "PT7S")
                .containsEntry("max-pool-size", "25");
    }

    @Test
    void normalizesDotUnderscoreAndCaseToKebab() {
        // Relaxed env binding (ACCESSFLOW_PROXY_ENGINES_MONGODB_CONNECT_TIMEOUT) arrives with
        // dotted inner keys; other sources may carry underscores or upper case.
        var properties = new EngineConfigProperties(Map.of("mongodb", Map.of(
                "connect.timeout", "PT5S",
                "server_selection_timeout", "PT9S",
                "MAX-POOL-SIZE", "42")));

        assertThat(properties.forEngine("mongodb"))
                .containsEntry("connect-timeout", "PT5S")
                .containsEntry("server-selection-timeout", "PT9S")
                .containsEntry("max-pool-size", "42")
                .hasSize(3);
    }

    @Test
    void nonCanonicalKeyOverridesCanonicalOnCollision() {
        // YAML declares the canonical default; an env-derived (dotted) key must win regardless of
        // map iteration order.
        var yamlFirst = new LinkedHashMap<String, String>();
        yamlFirst.put("connect-timeout", "PT10S");
        yamlFirst.put("connect.timeout", "PT5S");
        var envFirst = new LinkedHashMap<String, String>();
        envFirst.put("connect.timeout", "PT5S");
        envFirst.put("connect-timeout", "PT10S");

        assertThat(new EngineConfigProperties(Map.of("mongodb", yamlFirst)).forEngine("mongodb"))
                .containsEntry("connect-timeout", "PT5S").hasSize(1);
        assertThat(new EngineConfigProperties(Map.of("mongodb", envFirst)).forEngine("mongodb"))
                .containsEntry("connect-timeout", "PT5S").hasSize(1);
    }
}
