package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.couchbase.client.core.deps.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import com.couchbase.client.core.env.SecurityConfig;

import java.util.function.Consumer;

/**
 * Builds the Couchbase connection spec for a datasource descriptor: the
 * {@code couchbase://} / {@code couchbases://} connection string plus the matching
 * {@link SecurityConfig} customization. The same spec is consumed by the query path
 * ({@link CouchbaseClusterManager}) and by the admin connection test / schema introspection, so
 * the TLS mapping lives in one place.
 *
 * <p>When {@link DatasourceConnectionDescriptor#jdbcUrlOverride()} is set it is treated as a
 * verbatim Couchbase connection string (the operator owns all options, including
 * {@code couchbases://} and any {@code ?param} tuning); otherwise the string is assembled from
 * host/port and the {@code SslMode} maps as: {@code DISABLE} → plain {@code couchbase://};
 * {@code REQUIRE} → {@code couchbases://} that trusts any certificate (encrypt, don't validate);
 * {@code VERIFY_CA} → {@code couchbases://} with default trust but no hostname verification;
 * {@code VERIFY_FULL} → SDK defaults. Credentials never appear in the connection string — they go
 * through {@code ClusterOptions} at connect time. Note Couchbase TLS bootstraps on port 11207
 * (plain KV: 11210); the operator sets the matching port or a URL override.
 */
final class CouchbaseConnectionStringFactory {

    /** Connection string + the security customization to apply on the cluster environment. */
    record ConnectionSpec(String connectionString, Consumer<SecurityConfig.Builder> security) {
    }

    ConnectionSpec build(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            return new ConnectionSpec(descriptor.jdbcUrlOverride().strip(), security -> { });
        }
        var host = descriptor.host() == null ? "localhost" : descriptor.host();
        var scheme = switch (descriptor.sslMode()) {
            case DISABLE -> "couchbase://";
            case REQUIRE, VERIFY_CA, VERIFY_FULL -> "couchbases://";
        };
        var address = descriptor.port() == null ? host : host + ":" + descriptor.port();
        Consumer<SecurityConfig.Builder> security = switch (descriptor.sslMode()) {
            case DISABLE, VERIFY_FULL -> builder -> { };
            // REQUIRE encrypts but does not validate the server certificate.
            case REQUIRE -> builder -> builder
                    .trustManagerFactory(InsecureTrustManagerFactory.INSTANCE)
                    .enableHostnameVerification(false);
            case VERIFY_CA -> builder -> builder.enableHostnameVerification(false);
        };
        return new ConnectionSpec(scheme + address, security);
    }
}
