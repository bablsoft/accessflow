package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.http.HttpTransportOptions;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Options-assembly only — no network calls are made ({@code options()} never dials out). */
class BigQueryClientFactoryTest {

    private static final BigQueryEngineSettings SETTINGS = new BigQueryEngineSettings(
            Duration.ofSeconds(7), Duration.ofSeconds(21));

    @Test
    void parsesProjectAndOptionalDefaultDataset() {
        assertThat(BigQueryClientFactory.ProjectTarget.parse("my-project"))
                .isEqualTo(new BigQueryClientFactory.ProjectTarget("my-project", null));
        assertThat(BigQueryClientFactory.ProjectTarget.parse(" my-project.analytics "))
                .isEqualTo(new BigQueryClientFactory.ProjectTarget("my-project", "analytics"));
        assertThat(BigQueryClientFactory.ProjectTarget.parse(null))
                .isEqualTo(new BigQueryClientFactory.ProjectTarget("", null));
    }

    @Test
    void emulatorOverrideUsesCustomHostAndNoCredentials() {
        var factory = new BigQueryClientFactory(c -> {
            throw new AssertionError("credentials must not be decrypted on the emulator path");
        }, SETTINGS, TestMessages.keyEcho());
        var options = factory.options(descriptor("test.dataset1", "http://localhost:9050"));
        assertThat(options.getHost()).isEqualTo("http://localhost:9050");
        assertThat(options.getProjectId()).isEqualTo("test");
        assertThat(options.getCredentials()).isSameAs(NoCredentials.getInstance());
    }

    @Test
    void appliesTransportTimeoutsFromSettings() {
        var factory = new BigQueryClientFactory(c -> c, SETTINGS, TestMessages.keyEcho());
        var options = factory.options(descriptor("test", "http://localhost:9050"));
        var transport = (HttpTransportOptions) options.getTransportOptions();
        assertThat(transport.getConnectTimeout()).isEqualTo(7000);
        assertThat(transport.getReadTimeout()).isEqualTo(21000);
    }

    @Test
    void realPathParsesServiceAccountKeyJson() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        var keyJson = """
                {"type": "service_account", "project_id": "my-project",
                 "private_key_id": "k1", "private_key": "%s",
                 "client_email": "sa@my-project.iam.gserviceaccount.com", "client_id": "1",
                 "token_uri": "https://oauth2.googleapis.com/token"}""".formatted(pem);
        var factory = new BigQueryClientFactory(c -> keyJson, SETTINGS, TestMessages.keyEcho());
        var options = factory.options(descriptor("my-project", null));
        assertThat(options.getCredentials()).isInstanceOf(ServiceAccountCredentials.class);
        assertThat(((ServiceAccountCredentials) options.getCredentials()).getClientEmail())
                .isEqualTo("sa@my-project.iam.gserviceaccount.com");
        assertThat(options.getProjectId()).isEqualTo("my-project");
    }

    @Test
    void invalidKeyJsonRaisesInvalidCredentials() {
        var factory = new BigQueryClientFactory(c -> "not json at all", SETTINGS,
                TestMessages.keyEcho());
        assertThatThrownBy(() -> factory.options(descriptor("my-project", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error.bigquery.invalid_credentials");
    }

    @Test
    void settingsComeFromTheGenericEngineConfigLane() {
        var settings = BigQueryEngineSettings.from(Map.of("connect-timeout", "PT3S"));
        var factory = new BigQueryClientFactory(c -> c, settings, TestMessages.keyEcho());
        var transport = (HttpTransportOptions) factory
                .options(descriptor("p", "http://localhost:1")).getTransportOptions();
        assertThat(transport.getConnectTimeout()).isEqualTo(3000);
        assertThat(transport.getReadTimeout()).isEqualTo(30000);
    }

    private static DatasourceConnectionDescriptor descriptor(String databaseName, String endpoint) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.BIGQUERY, null, null, databaseName, null, "cipher", SslMode.DISABLE, 10,
                1000, true, null, false, null, "bigquery", endpoint, null, null, null, true, null);
    }
}
