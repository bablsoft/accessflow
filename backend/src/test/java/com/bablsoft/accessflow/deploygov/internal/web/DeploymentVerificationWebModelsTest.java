package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentVerificationWebModelsTest {

    @Test
    void httpCheckDefaultsExpectedStatusAndDropsBlankBody() {
        var request = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH,
                "health", "https://svc/healthz", null, " ", null, null, null, null, null);

        assertThat(request.toCheck())
                .isEqualTo(new HttpHealthCheck("health", "https://svc/healthz", 200, null));
    }

    @Test
    void httpCheckKeepsExplicitStatusAndBody() {
        var request = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH,
                "health", "https://svc/healthz", 204, "ok", null, null, null, null, null);

        assertThat(request.toCheck())
                .isEqualTo(new HttpHealthCheck("health", "https://svc/healthz", 204, "ok"));
    }

    @Test
    void promqlCheckDefaultsNoDataToFailure() {
        var request = new DeploymentVerificationCheckRequest(VerificationCheckKind.PROMQL_THRESHOLD,
                "p", null, null, null, "https://prom", "up", VerificationComparator.GTE, 1.0, null);

        assertThat(request.toCheck()).isEqualTo(new PromqlThresholdCheck("p", "https://prom", "up",
                VerificationComparator.GTE, 1.0, true));
    }

    @Test
    void promqlCheckKeepsExplicitNoDataFlag() {
        var request = new DeploymentVerificationCheckRequest(VerificationCheckKind.PROMQL_THRESHOLD,
                "p", null, null, null, "https://prom", "up", VerificationComparator.LT, 2.5, false);

        assertThat(((PromqlThresholdCheck) request.toCheck()).noDataIsFailure()).isFalse();
    }

    @Test
    void configRequestMapsEveryCheck() {
        var request = new DeploymentVerificationConfigRequest(Duration.ofMinutes(15),
                Duration.ofMinutes(1), 3, List.of(new DeploymentVerificationCheckRequest(
                        VerificationCheckKind.HTTP_HEALTH, "health", "https://svc", null, null, null,
                        null, null, null, null)));

        var config = DeploymentVerificationConfigRequest.toConfigOrNull(request);

        assertThat(config).isEqualTo(new DeploymentVerificationConfig(Duration.ofMinutes(15),
                Duration.ofMinutes(1), 3, List.of(new HttpHealthCheck("health", "https://svc", 200,
                        null))));
        assertThat(DeploymentVerificationConfigRequest.toConfigOrNull(null)).isNull();
    }

    @Test
    void environmentRequestsThreadTheConfigIntoTheirCommands() {
        var configRequest = new DeploymentVerificationConfigRequest(Duration.ofMinutes(5),
                Duration.ofSeconds(30), 1, List.of(new DeploymentVerificationCheckRequest(
                        VerificationCheckKind.HTTP_HEALTH, "h", "https://svc", null, null, null, null,
                        null, null, null)));

        var create = new CreateDeploymentEnvironmentRequest("prod", null, null, null, null, null,
                null, null, null, configRequest).toCommand();
        var update = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, configRequest, true).toCommand();

        assertThat(create.verification()).isEqualTo(configRequest.toConfig());
        assertThat(update.verification()).isEqualTo(configRequest.toConfig());
        assertThat(update.clearVerification()).isTrue();
    }

    @Test
    void responseRendersBothKindsFlatWithIsoDurations() {
        var config = new DeploymentVerificationConfig(Duration.ofMinutes(15), Duration.ofMinutes(1), 3,
                List.of(new HttpHealthCheck("health", "https://svc", 200, "ok"),
                        new PromqlThresholdCheck("p", "https://prom", "up", VerificationComparator.LT,
                                0.05, true)));
        var view = new DeploymentEnvironmentView(UUID.randomUUID(), UUID.randomUUID(), "prod", 0,
                true, null, null, false, Instant.EPOCH, List.of(), null, false, config);

        var response = DeploymentEnvironmentResponse.from(view).verification();

        assertThat(response.window()).isEqualTo("PT15M");
        assertThat(response.sampleInterval()).isEqualTo("PT1M");
        assertThat(response.failureThreshold()).isEqualTo(3);
        assertThat(response.checks()).containsExactly(
                new DeploymentVerificationConfigResponse.Check(VerificationCheckKind.HTTP_HEALTH,
                        "health", "https://svc", 200, "ok", null, null, null, null, null),
                new DeploymentVerificationConfigResponse.Check(
                        VerificationCheckKind.PROMQL_THRESHOLD, "p", null, null, null,
                        "https://prom", "up", VerificationComparator.LT, 0.05, true));
    }

    @Test
    void responseOmitsAbsentConfigAndDurations() {
        assertThat(DeploymentVerificationConfigResponse.from(null)).isNull();
        var response = DeploymentVerificationConfigResponse.from(
                new DeploymentVerificationConfig(null, null, 1, null));

        assertThat(response.window()).isNull();
        assertThat(response.sampleInterval()).isNull();
        assertThat(response.checks()).isEmpty();
    }

    private static final JsonMapper SNAKE_CASE_MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @Test
    void wireJsonBindsIsoDurationsAndSnakeCaseFields() {
        var request = SNAKE_CASE_MAPPER.readValue("""
                {"window":"PT15M","sample_interval":"PT1M","failure_threshold":3,"checks":[
                  {"kind":"PROMQL_THRESHOLD","name":"p","prometheus_url":"https://prom",
                   "query":"up","comparator":"LT","threshold":0.5,"no_data_is_failure":false}]}
                """, DeploymentVerificationConfigRequest.class);

        assertThat(request.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(request.checks().get(0).prometheusUrl()).isEqualTo("https://prom");
        assertThat(request.checks().get(0).noDataIsFailure()).isFalse();
    }

    @Test
    void malformedDurationOrUnknownKindFailsDeserializationNotValidation() {
        assertThatThrownBy(() -> SNAKE_CASE_MAPPER.readValue("""
                {"window":"fifteen minutes","sample_interval":"PT1M","failure_threshold":1,"checks":[]}
                """, DeploymentVerificationConfigRequest.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> SNAKE_CASE_MAPPER.readValue("""
                {"kind":"DATADOG","name":"d"}
                """, DeploymentVerificationCheckRequest.class))
                .isInstanceOf(JacksonException.class);
    }
}
