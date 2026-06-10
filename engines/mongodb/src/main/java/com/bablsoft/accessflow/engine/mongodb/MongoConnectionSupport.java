package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.engine.mongodb.MongoConnectionStringFactory.MongoClientOptions;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.time.Duration;

/**
 * Shared connection helpers for the admin paths (connection test + schema introspection). Builds a
 * short-lived {@link MongoClient} from a datasource descriptor using the shared
 * {@link MongoConnectionStringFactory}, with tight admin-time timeouts so a misconfigured
 * datasource fails fast in the UI.
 */
final class MongoConnectionSupport {

    private static final MongoClientOptions ADMIN_OPTIONS = new MongoClientOptions(
            Duration.ofSeconds(5), Duration.ofSeconds(5), 2);

    private MongoConnectionSupport() {
    }

    static MongoClient openClient(DatasourceConnectionDescriptor descriptor,
                                  CredentialDecryptor credentials,
                                  MongoConnectionStringFactory connectionStringFactory) {
        var password = descriptor.passwordEncrypted() == null
                || descriptor.passwordEncrypted().isBlank()
                ? ""
                : credentials.decrypt(descriptor.passwordEncrypted());
        var uri = connectionStringFactory.build(descriptor, password, ADMIN_OPTIONS);
        return MongoClients.create(uri);
    }

    static String databaseName(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            return descriptor.databaseName();
        }
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            var db = new ConnectionString(descriptor.jdbcUrlOverride().strip()).getDatabase();
            if (db != null && !db.isBlank()) {
                return db;
            }
        }
        return "admin";
    }
}
