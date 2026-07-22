package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import net.snowflake.client.api.driver.SnowflakeDriver;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens a Snowflake JDBC {@link Connection} from a {@link DatasourceConnectionDescriptor} — the
 * single place that maps AccessFlow connection fields onto the driver. The driver is instantiated
 * directly ({@code new SnowflakeDriver()}), never through {@code DriverManager} — the plugin lives
 * in an isolated classloader that {@code DriverManager}'s caller-visibility rules would reject.
 *
 * <p>URL: a non-blank {@code jdbc_url_override} is used verbatim (it may carry
 * {@code warehouse=…&role=…&schema=…} parameters) and must start with {@code jdbc:snowflake://};
 * otherwise the URL is {@code jdbc:snowflake://<host>} (the account host, e.g.
 * {@code myorg-myacct.snowflakecomputing.com}). {@code database_name} maps to the {@code db}
 * property, {@code username} to {@code user}. The decrypted credential is a password <em>or</em>
 * an unencrypted PKCS#8 private-key PEM (key-pair auth, detected by its {@code -----BEGIN}
 * header and passed as the object-valued {@code privateKey} property); passphrase-protected PEMs
 * are rejected. Credentials are decrypted only here, at connection construction, mirroring the
 * host rule that plaintext lives no longer than pool init.
 *
 * <p>Deliberately <em>per-request</em>: every call opens a fresh connection and the caller closes
 * it — no pool, no cache. Warehouse sessions are billed while resumed, governance traffic is
 * sparse, and a fresh session cannot leak {@code USE}-style session state between requests.
 */
class SnowflakeConnectionFactory {

    static final String URL_PREFIX = "jdbc:snowflake://";

    private final CredentialDecryptor credentials;
    private final SnowflakeEngineSettings settings;

    SnowflakeConnectionFactory(CredentialDecryptor credentials, SnowflakeEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    Connection open(DatasourceConnectionDescriptor descriptor) throws SQLException {
        var url = url(descriptor);
        var properties = connectionProperties(descriptor);
        var connection = new SnowflakeDriver().connect(url, properties);
        if (connection == null) {
            // Defensive: the prefix is validated above, so the driver always accepts the URL.
            throw new SQLException("Snowflake driver rejected URL " + url);
        }
        return connection;
    }

    /** The JDBC URL for the datasource; package-private for prop-assembly tests. */
    String url(DatasourceConnectionDescriptor descriptor) {
        var override = descriptor.jdbcUrlOverride();
        if (override != null && !override.isBlank()) {
            var trimmed = override.strip();
            if (!trimmed.startsWith(URL_PREFIX)) {
                throw new SnowflakeConfigException("error.snowflake.invalid_url_override", trimmed);
            }
            return trimmed;
        }
        return URL_PREFIX + descriptor.host();
    }

    /** The driver connection properties; package-private for prop-assembly tests. */
    Properties connectionProperties(DatasourceConnectionDescriptor descriptor) {
        var properties = new Properties();
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            properties.put("db", descriptor.databaseName().strip());
        }
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            properties.put("user", descriptor.username().strip());
        }
        properties.put("loginTimeout", String.valueOf(settings.loginTimeout().toSeconds()));
        properties.put("networkTimeout", String.valueOf(settings.networkTimeout().toMillis()));
        applyCredential(properties, credentials.decrypt(descriptor.passwordEncrypted()));
        return properties;
    }

    private static void applyCredential(Properties properties, String credential) {
        if (SnowflakePrivateKeyParser.isEncryptedPrivateKeyPem(credential)) {
            throw new SnowflakeConfigException("error.snowflake.encrypted_private_key_unsupported");
        }
        if (SnowflakePrivateKeyParser.isPrivateKeyPem(credential)) {
            properties.put("privateKey", SnowflakePrivateKeyParser.parse(credential.strip()));
            return;
        }
        if (credential != null) {
            properties.put("password", credential);
        }
    }
}
