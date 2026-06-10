package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.SslMode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a MongoDB connection-string URI ({@code mongodb://…}) for a datasource descriptor. The
 * same URI is consumed by the query path ({@link MongoClientManager}) and by the admin connection
 * test / schema introspection, so the credential handling, TLS mapping, and timeout encoding live
 * in one place.
 *
 * <p>When {@link DatasourceConnectionDescriptor#jdbcUrlOverride()} is set it is treated as a
 * verbatim MongoDB connection string (the operator owns all options); otherwise the URI is
 * assembled from the host/port/database/credentials/SSL fields. Credentials are URL-encoded; the
 * plaintext password is supplied by the caller (decrypted via the host's
 * {@code CredentialDecryptor}) — never the ciphertext. Field-based URIs default
 * {@code authSource=admin} (matching the standard MongoDB Docker image's root user); datasources
 * whose user lives in the target database — or that need replica-set hosts, {@code mongodb+srv},
 * or other options — should set a verbatim connection string via the datasource's URL override.
 */
final class MongoConnectionStringFactory {

    /**
     * @param descriptor        the datasource connection descriptor
     * @param decryptedPassword the already-decrypted password (may be blank for keyless connections)
     * @param options           client timeouts and pool size to encode as URI query parameters
     * @return a {@code mongodb://} (or {@code mongodb+srv://}) connection string
     */
    String build(DatasourceConnectionDescriptor descriptor, String decryptedPassword,
                 MongoClientOptions options) {
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            return descriptor.jdbcUrlOverride().strip();
        }
        var host = descriptor.host() == null ? "localhost" : descriptor.host();
        var port = descriptor.port() == null ? 27017 : descriptor.port();
        var sb = new StringBuilder("mongodb://");
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            sb.append(encode(descriptor.username()));
            if (decryptedPassword != null && !decryptedPassword.isEmpty()) {
                sb.append(':').append(encode(decryptedPassword));
            }
            sb.append('@');
        }
        sb.append(host).append(':').append(port).append('/');
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            sb.append(encode(descriptor.databaseName()));
        }
        sb.append('?').append(String.join("&", queryParams(descriptor, options)));
        return sb.toString();
    }

    private static List<String> queryParams(DatasourceConnectionDescriptor descriptor,
                                            MongoClientOptions options) {
        var params = new ArrayList<String>();
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            params.add("authSource=admin");
        }
        params.addAll(tlsParams(descriptor.sslMode()));
        params.add("connectTimeoutMS=" + options.connectTimeout().toMillis());
        params.add("serverSelectionTimeoutMS=" + options.serverSelectionTimeout().toMillis());
        params.add("maxPoolSize=" + options.maxPoolSize());
        return params;
    }

    private static List<String> tlsParams(SslMode sslMode) {
        return switch (sslMode) {
            case DISABLE -> List.of("tls=false");
            // REQUIRE encrypts but does not validate the server certificate.
            case REQUIRE -> List.of("tls=true", "tlsAllowInvalidCertificates=true");
            case VERIFY_CA, VERIFY_FULL -> List.of("tls=true");
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Client-level options encoded as connection-string query parameters. */
    record MongoClientOptions(Duration connectTimeout, Duration serverSelectionTimeout,
                              int maxPoolSize) {
    }
}
