package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec.VerificationConfigParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentVerificationConfigCodecTest {

    private final DeploymentVerificationConfigCodec codec =
            new DeploymentVerificationConfigCodec(JsonMapper.builder().build());

    static DeploymentVerificationConfig sampleConfig() {
        return new DeploymentVerificationConfig(Duration.ofMinutes(15), Duration.ofMinutes(1), 3,
                List.of(new HttpHealthCheck("health", "https://svc.example.com/healthz", 200, "ok"),
                        new PromqlThresholdCheck("error-rate", "https://prom.example.com",
                                "sum(rate(http_errors_total[5m]))", VerificationComparator.LT, 0.05,
                                true)));
    }

    @Test
    void roundTripsBothKinds() {
        var config = sampleConfig();

        var json = codec.toJson(config);

        assertThat(json).contains("\"kind\":\"HTTP_HEALTH\"", "\"kind\":\"PROMQL_THRESHOLD\"",
                "\"window\":\"PT15M\"", "\"sampleInterval\":\"PT1M\"");
        assertThat(codec.fromJson(json)).isEqualTo(config);
    }

    @Test
    void omitsAbsentBodyContainsAndRoundTripsItAsNull() {
        var config = new DeploymentVerificationConfig(Duration.ofMinutes(5), Duration.ofSeconds(30),
                1, List.of(new HttpHealthCheck("health", "https://svc/healthz", 204, null)));

        var json = codec.toJson(config);

        assertThat(json).doesNotContain("bodyContains");
        assertThat(codec.fromJson(json)).isEqualTo(config);
    }

    @Test
    void parsesLowercaseEnumValues() {
        var json = """
                {"window":"PT5M","sampleInterval":"PT1M","failureThreshold":2,"checks":[
                  {"kind":"promql_threshold","name":"p","prometheusUrl":"https://prom","query":"up",
                   "comparator":"gte","threshold":1,"noDataIsFailure":false}]}
                """;

        var config = codec.fromJson(json);

        assertThat(config.checks()).containsExactly(new PromqlThresholdCheck("p", "https://prom",
                "up", VerificationComparator.GTE, 1.0, false));
    }

    @Test
    void nullConfigEncodesAsNull() {
        assertThat(codec.toJson(null)).isNull();
    }

    @Test
    void nullBlankAndJsonNullDecodeAsNull() {
        assertThat(codec.fromJson(null)).isNull();
        assertThat(codec.fromJson("  ")).isNull();
        assertThat(codec.fromJson("null")).isNull();
    }

    @Test
    void rejectsUnknownKind() {
        var json = """
                {"window":"PT5M","sampleInterval":"PT1M","failureThreshold":2,
                 "checks":[{"kind":"DATADOG_MONITOR","name":"d"}]}
                """;

        assertThatThrownBy(() -> codec.fromJson(json))
                .isInstanceOf(VerificationConfigParseException.class)
                .hasMessageContaining("Unknown kind");
    }

    @Test
    void rejectsMalformedDuration() {
        var json = """
                {"window":"fifteen minutes","sampleInterval":"PT1M","failureThreshold":2,"checks":[]}
                """;

        assertThatThrownBy(() -> codec.fromJson(json))
                .isInstanceOf(VerificationConfigParseException.class)
                .hasMessageContaining("window");
    }

    @Test
    void rejectsUnknownComparator() {
        var json = """
                {"window":"PT5M","sampleInterval":"PT1M","failureThreshold":2,"checks":[
                  {"kind":"PROMQL_THRESHOLD","name":"p","prometheusUrl":"https://prom","query":"up",
                   "comparator":"EQ","threshold":1,"noDataIsFailure":true}]}
                """;

        assertThatThrownBy(() -> codec.fromJson(json))
                .isInstanceOf(VerificationConfigParseException.class)
                .hasMessageContaining("comparator");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":{}}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":\"2\",\"checks\":[]}",
            "{\"window\":5,\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":[]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":[1]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":"
                    + "[{\"kind\":\"HTTP_HEALTH\",\"name\":\"h\",\"expectedStatus\":200}]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":"
                    + "[{\"kind\":\"HTTP_HEALTH\",\"name\":\"\",\"url\":\"u\",\"expectedStatus\":200}]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":"
                    + "[{\"kind\":\"PROMQL_THRESHOLD\",\"name\":\"p\",\"prometheusUrl\":\"u\","
                    + "\"query\":\"q\",\"comparator\":\"LT\",\"threshold\":\"x\",\"noDataIsFailure\":true}]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":"
                    + "[{\"kind\":\"PROMQL_THRESHOLD\",\"name\":\"p\",\"prometheusUrl\":\"u\","
                    + "\"query\":\"q\",\"comparator\":\"LT\",\"threshold\":1,\"noDataIsFailure\":\"y\"}]}",
            "{\"window\":\"PT5M\",\"sampleInterval\":\"PT1M\",\"failureThreshold\":2,\"checks\":"
                    + "[{\"kind\":\"HTTP_HEALTH\",\"name\":\"h\",\"url\":\"u\",\"expectedStatus\":200,"
                    + "\"bodyContains\":7}]}"
    })
    void rejectsMalformedBlobs(String json) {
        assertThatThrownBy(() -> codec.fromJson(json))
                .isInstanceOf(VerificationConfigParseException.class);
    }
}
