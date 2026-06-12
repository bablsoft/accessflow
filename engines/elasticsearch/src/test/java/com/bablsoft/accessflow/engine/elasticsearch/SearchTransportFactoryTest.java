package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTransportFactoryTest {

    private static final CredentialDecryptor DECRYPTOR = ciphertext -> "dec(" + ciphertext + ")";
    private final SearchTransportFactory factory = new SearchTransportFactory(DECRYPTOR,
            ElasticsearchEngineSettings.from(null), TransportFlavor.ELASTICSEARCH);

    private static DatasourceConnectionDescriptor descriptor(Integer port, SslMode sslMode,
                                                             String username, String passwordEnc,
                                                             String jdbcUrlOverride,
                                                             String apiKeyEnc) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.ELASTICSEARCH, "search.example.com", port, null, username, passwordEnc,
                sslMode, 10, 1000, false, null, false, null, "elasticsearch", jdbcUrlOverride,
                null, null, null, true, null, apiKeyEnc);
    }

    private EsConnectionConfig resolve(DatasourceConnectionDescriptor descriptor) {
        return factory.resolveConfig(descriptor, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    @Test
    void resolvesHttpsForRequireAndHttpForDisableWithDefaultPort() {
        var https = resolve(descriptor(null, SslMode.REQUIRE, "u", "p", null, null));
        assertThat(https.scheme()).isEqualTo("https");
        assertThat(https.host()).isEqualTo("search.example.com");
        assertThat(https.port()).isEqualTo(9200);

        var http = resolve(descriptor(9300, SslMode.DISABLE, "u", "p", null, null));
        assertThat(http.scheme()).isEqualTo("http");
        assertThat(http.port()).isEqualTo(9300);
    }

    @Test
    void parsesVerbatimBaseUrlOverride() {
        var config = resolve(descriptor(null, SslMode.REQUIRE, "u", "p",
                "https://es.internal:9201", null));
        assertThat(config.scheme()).isEqualTo("https");
        assertThat(config.host()).isEqualTo("es.internal");
        assertThat(config.port()).isEqualTo(9201);
    }

    @Test
    void prefersApiKeyAuthWhenPresent() {
        var config = resolve(descriptor(null, SslMode.REQUIRE, "u", "p", null, "ENC_KEY"));
        assertThat(config.auth()).isInstanceOf(EsAuth.ApiKey.class);
        assertThat(((EsAuth.ApiKey) config.auth()).token()).isEqualTo("dec(ENC_KEY)");
    }

    @Test
    void usesBasicAuthWhenUsernamePresentAndNoApiKey() {
        var config = resolve(descriptor(null, SslMode.REQUIRE, "elastic", "ENC_PW", null, null));
        assertThat(config.auth()).isInstanceOf(EsAuth.Basic.class);
        assertThat(((EsAuth.Basic) config.auth()).username()).isEqualTo("elastic");
        assertThat(((EsAuth.Basic) config.auth()).password()).isEqualTo("dec(ENC_PW)");
    }

    @Test
    void usesNoAuthWhenNeitherCredentialIsPresent() {
        var config = resolve(descriptor(null, SslMode.DISABLE, "", "", null, null));
        assertThat(config.auth()).isEqualTo(EsAuth.None.INSTANCE);
        assertThat(config.trustAll()).isFalse();
    }
}
