package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.SslMode;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/**
 * Builds a native {@link CqlSession} from a {@link DatasourceConnectionDescriptor} — the single
 * place that maps AccessFlow connection fields onto the DataStax driver. Contact point from
 * host/port (default 9042), {@code withLocalDatacenter(...)} from the per-datasource
 * {@code local_datacenter} field (required for the driver's default load-balancing policy), the
 * datasource keyspace as the session's default keyspace, auth from username + decrypted password,
 * and SSL when {@code ssl_mode != DISABLE}. Timeouts come from {@link CassandraEngineSettings}.
 * Credentials are decrypted only here, at session-construction time, mirroring the host rule that
 * plaintext lives no longer than pool initialization.
 */
final class CassandraSessionFactory {

    private static final int DEFAULT_PORT = 9042;

    private final CredentialDecryptor credentials;
    private final CassandraEngineSettings settings;

    CassandraSessionFactory(CredentialDecryptor credentials, CassandraEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    CqlSession open(DatasourceConnectionDescriptor descriptor) {
        int port = descriptor.port() != null ? descriptor.port() : DEFAULT_PORT;
        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(descriptor.host(), port))
                .withConfigLoader(configLoader());
        if (descriptor.localDatacenter() != null && !descriptor.localDatacenter().isBlank()) {
            builder.withLocalDatacenter(descriptor.localDatacenter().strip());
        }
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            builder.withKeyspace(CqlIdentifier.fromCql(descriptor.databaseName().strip()));
        }
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            builder.withAuthCredentials(descriptor.username(),
                    credentials.decrypt(descriptor.passwordEncrypted()));
        }
        if (descriptor.sslMode() != null && descriptor.sslMode() != SslMode.DISABLE) {
            builder.withSslContext(sslContext(descriptor.sslMode()));
        }
        return builder.build();
    }

    private DriverConfigLoader configLoader() {
        return DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, settings.requestTimeout())
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, settings.connectTimeout())
                .build();
    }

    /**
     * REQUIRE encrypts the channel without verifying the server certificate (parity with the JDBC
     * engines' {@code trustServerCertificate=true}); VERIFY_CA / VERIFY_FULL use the JVM default
     * trust store.
     */
    private static SSLContext sslContext(SslMode sslMode) {
        try {
            if (sslMode == SslMode.REQUIRE) {
                var ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{TRUST_ALL}, null);
                return ctx;
            }
            return SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize TLS for Cassandra connection", e);
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Intentionally permissive: REQUIRE encrypts without certificate verification.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Intentionally permissive: REQUIRE encrypts without certificate verification.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
