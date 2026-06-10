package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.MongoConnectionStringFactory;
import com.bablsoft.accessflow.core.api.SslMode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link MongoConnectionStringFactory}. Pure string assembly — no MongoDB driver types — so
 * the {@code core} (connection test / introspection) and {@code proxy} (query execution) modules can
 * both build clients from one source of truth without a cyclic dependency.
 *
 * <p>Field-based URIs default {@code authSource=admin} (matching the standard MongoDB Docker image's
 * root user). Datasources whose user lives in the target database — or that need replica-set hosts,
 * {@code mongodb+srv}, or other options — should set a verbatim connection string via the
 * datasource's URL override.
 */
@Component
public class DefaultMongoConnectionStringFactory implements MongoConnectionStringFactory {

    @Override
    public String build(DatasourceConnectionDescriptor descriptor, String decryptedPassword,
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
}
