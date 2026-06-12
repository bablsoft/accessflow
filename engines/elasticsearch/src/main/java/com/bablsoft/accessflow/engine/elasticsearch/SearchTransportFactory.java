package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.SslMode;

import java.net.URI;
import java.time.Duration;

/**
 * Builds a {@link SearchTransport} from a {@link DatasourceConnectionDescriptor} — the single place
 * that maps AccessFlow connection fields onto a low-level REST client. The base URL is the verbatim
 * {@code jdbc_url_override} when set (e.g. {@code https://host:9200}), otherwise host/port (default
 * 9200) + scheme from {@code ssl_mode}. Auth is API key (the {@code api_key_encrypted} column,
 * decrypted) when present, else HTTP basic (username + decrypted password), else none. Credentials
 * are decrypted only here, at client-construction time, mirroring the host rule that plaintext lives
 * no longer than pool initialization. The {@link TransportFlavor} selects the Elastic vs OpenSearch
 * client.
 */
final class SearchTransportFactory {

    private static final int DEFAULT_PORT = 9200;

    private final CredentialDecryptor credentials;
    private final ElasticsearchEngineSettings settings;
    private final TransportFlavor flavor;

    SearchTransportFactory(CredentialDecryptor credentials, ElasticsearchEngineSettings settings,
                           TransportFlavor flavor) {
        this.credentials = credentials;
        this.settings = settings;
        this.flavor = flavor;
    }

    SearchTransport create(DatasourceConnectionDescriptor descriptor) {
        return transport(resolveConfig(descriptor, settings.connectTimeout(),
                settings.socketTimeout()));
    }

    /** Short-lived transport with tight timeouts for the admin probe / introspection paths. */
    SearchTransport createAdmin(DatasourceConnectionDescriptor descriptor) {
        return transport(resolveConfig(descriptor, settings.adminConnectTimeout(),
                settings.adminSocketTimeout()));
    }

    private SearchTransport transport(EsConnectionConfig config) {
        return switch (flavor) {
            case ELASTICSEARCH -> new ElasticTransport(config);
            case OPENSEARCH -> new OpenSearchTransport(config);
        };
    }

    /** Resolve the driver-agnostic connection config; package-private for unit testing. */
    EsConnectionConfig resolveConfig(DatasourceConnectionDescriptor descriptor,
                                     Duration connectTimeout, Duration socketTimeout) {
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            var uri = URI.create(descriptor.jdbcUrlOverride().strip());
            var scheme = uri.getScheme() != null ? uri.getScheme() : "https";
            var port = uri.getPort() != -1 ? uri.getPort() : DEFAULT_PORT;
            return new EsConnectionConfig(scheme, uri.getHost(), port, resolveAuth(descriptor),
                    descriptor.sslMode(), connectTimeout, socketTimeout);
        }
        int port = descriptor.port() != null ? descriptor.port() : DEFAULT_PORT;
        var scheme = descriptor.sslMode() != null && descriptor.sslMode() != SslMode.DISABLE
                ? "https" : "http";
        return new EsConnectionConfig(scheme, descriptor.host(), port, resolveAuth(descriptor),
                descriptor.sslMode(), connectTimeout, socketTimeout);
    }

    private EsAuth resolveAuth(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.apiKeyEncrypted() != null && !descriptor.apiKeyEncrypted().isBlank()) {
            return new EsAuth.ApiKey(credentials.decrypt(descriptor.apiKeyEncrypted()));
        }
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            return new EsAuth.Basic(descriptor.username(),
                    credentials.decrypt(descriptor.passwordEncrypted()));
        }
        return EsAuth.None.INSTANCE;
    }
}
