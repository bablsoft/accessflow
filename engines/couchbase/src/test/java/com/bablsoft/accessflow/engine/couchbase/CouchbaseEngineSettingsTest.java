package com.bablsoft.accessflow.engine.couchbase;

import com.couchbase.client.java.query.QueryScanConsistency;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseEngineSettingsTest {

    @Test
    void defaultsWhenConfigIsEmpty() {
        var settings = CouchbaseEngineSettings.from(Map.of());
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.waitUntilReadyTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.scanConsistency()).isEqualTo(QueryScanConsistency.REQUEST_PLUS);
    }

    @Test
    void parsesIsoDurationsAndConsistency() {
        var settings = CouchbaseEngineSettings.from(Map.of(
                "connect-timeout", "PT5S",
                "wait-until-ready-timeout", "PT30S",
                "scan-consistency", "not-bounded"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.waitUntilReadyTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.scanConsistency()).isEqualTo(QueryScanConsistency.NOT_BOUNDED);
    }

    @Test
    void acceptsUnderscoreConsistencySpelling() {
        assertThat(CouchbaseEngineSettings.from(Map.of("scan-consistency", "NOT_BOUNDED"))
                .scanConsistency()).isEqualTo(QueryScanConsistency.NOT_BOUNDED);
    }

    @Test
    void fallsBackOnUnparseableValues() {
        var settings = CouchbaseEngineSettings.from(Map.of(
                "connect-timeout", "soon",
                "wait-until-ready-timeout", " ",
                "scan-consistency", "eventually"));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.waitUntilReadyTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.scanConsistency()).isEqualTo(QueryScanConsistency.REQUEST_PLUS);
    }
}
