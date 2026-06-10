package com.bablsoft.accessflow.core.api;

import java.time.Duration;

/**
 * Builds a MongoDB connection-string URI ({@code mongodb://…}) for a {@link DbType#MONGODB}
 * datasource. The same URI is consumed by the proxy engine ({@code MongoClientManager}) and by the
 * admin connection-test / schema-introspection paths in {@code core}, so the credential handling,
 * TLS mapping, and timeout encoding live in one place. API-pure (no MongoDB driver types): the
 * driver-side {@code MongoClients.create(uri)} stays in each module's internal package.
 *
 * <p>When {@link DatasourceConnectionDescriptor#jdbcUrlOverride()} is set it is treated as a verbatim
 * MongoDB connection string (the operator owns all options); otherwise the URI is assembled from the
 * host/port/database/credentials/SSL fields. Credentials are URL-encoded. The plaintext password is
 * supplied by the caller (decrypted via {@link CredentialEncryptionService}) — never the ciphertext.
 */
public interface MongoConnectionStringFactory {

    /**
     * @param descriptor        the datasource connection descriptor
     * @param decryptedPassword the already-decrypted password (may be blank for keyless connections)
     * @param options           client timeouts and pool size to encode as URI query parameters
     * @return a {@code mongodb://} (or {@code mongodb+srv://}) connection string
     */
    String build(DatasourceConnectionDescriptor descriptor, String decryptedPassword,
                 MongoClientOptions options);

    /** Client-level options encoded as connection-string query parameters. */
    record MongoClientOptions(Duration connectTimeout, Duration serverSelectionTimeout,
                              int maxPoolSize) {

        public static MongoClientOptions defaults() {
            return new MongoClientOptions(Duration.ofSeconds(10), Duration.ofSeconds(10), 10);
        }
    }
}
