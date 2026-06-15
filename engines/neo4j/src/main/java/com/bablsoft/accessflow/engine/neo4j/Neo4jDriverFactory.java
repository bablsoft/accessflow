package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.SslMode;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;

import java.util.concurrent.TimeUnit;

/**
 * Builds a native Neo4j {@link Driver} from a {@link DatasourceConnectionDescriptor} — the single
 * place that maps AccessFlow connection fields onto the Bolt driver. The connection URI is either
 * supplied verbatim through {@code jdbc_url_override} (a full {@code bolt://} / {@code neo4j://}
 * URI, e.g. {@code neo4j+s://…} for Aura / clustered routing) or built from host/port with the
 * encryption encoded in the scheme from {@code ssl_mode}: {@code DISABLE} → {@code bolt://}
 * (plaintext), {@code REQUIRE} → {@code bolt+ssc://} (encrypted, trust any certificate — parity
 * with the JDBC engines' {@code trustServerCertificate=true}), {@code VERIFY_CA}/{@code VERIFY_FULL}
 * → {@code bolt+s://} (encrypted, verify against the system trust store). Auth is HTTP-basic from
 * username + decrypted password (or none when no username is set). {@code database_name} is not part
 * of the URI — it scopes the session, applied by the executor. Credentials are decrypted only here,
 * at driver-construction time, mirroring the host rule that plaintext lives no longer than pool init.
 */
final class Neo4jDriverFactory {

    private static final int DEFAULT_PORT = 7687;

    private final CredentialDecryptor credentials;
    private final Neo4jEngineSettings settings;

    Neo4jDriverFactory(CredentialDecryptor credentials, Neo4jEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    Driver open(DatasourceConnectionDescriptor descriptor) {
        return GraphDatabase.driver(uri(descriptor), authToken(descriptor), config());
    }

    private static String uri(DatasourceConnectionDescriptor descriptor) {
        var override = descriptor.jdbcUrlOverride();
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        int port = descriptor.port() != null ? descriptor.port() : DEFAULT_PORT;
        return scheme(descriptor.sslMode()) + "://" + descriptor.host() + ":" + port;
    }

    private static String scheme(SslMode sslMode) {
        if (sslMode == null) {
            return "bolt";
        }
        return switch (sslMode) {
            case DISABLE -> "bolt";
            case REQUIRE -> "bolt+ssc";
            case VERIFY_CA, VERIFY_FULL -> "bolt+s";
        };
    }

    private AuthToken authToken(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.username() == null || descriptor.username().isBlank()) {
            return AuthTokens.none();
        }
        return AuthTokens.basic(descriptor.username(), credentials.decrypt(descriptor.passwordEncrypted()));
    }

    private Config config() {
        return Config.builder()
                .withConnectionTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withMaxConnectionPoolSize(settings.maxConnectionPoolSize())
                .withLogging(Logging.slf4j())
                .build();
    }
}
