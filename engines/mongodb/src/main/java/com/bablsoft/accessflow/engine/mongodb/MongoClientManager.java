package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link MongoClient} per MongoDB datasource (the driver pools connections
 * internally), the document-engine analogue of the host's JDBC connection-pool manager. Clients
 * are evicted when the host calls {@code QueryEngine.evictDatasource} on datasource config-change
 * / deactivation. SELECT operations on a datasource with a configured read replica are routed
 * {@link ReadPreference#secondaryPreferred()}.
 */
class MongoClientManager {

    private static final Logger log = LoggerFactory.getLogger(MongoClientManager.class);

    private final CredentialDecryptor credentials;
    private final MongoConnectionStringFactory connectionStringFactory;
    private final MongoEngineSettings settings;
    private final Map<UUID, MongoClient> clients = new ConcurrentHashMap<>();

    MongoClientManager(CredentialDecryptor credentials,
                       MongoConnectionStringFactory connectionStringFactory,
                       MongoEngineSettings settings) {
        this.credentials = credentials;
        this.connectionStringFactory = connectionStringFactory;
        this.settings = settings;
    }

    /** The target database for a datasource, optionally routed to a secondary for reads. */
    MongoDatabase database(DatasourceConnectionDescriptor descriptor, boolean forRead) {
        var client = clients.computeIfAbsent(descriptor.id(), id -> create(descriptor));
        var database = client.getDatabase(resolveDatabaseName(descriptor));
        if (forRead && descriptor.hasReadReplica()) {
            return database.withReadPreference(ReadPreference.secondaryPreferred());
        }
        return database;
    }

    void evict(UUID datasourceId) {
        var client = clients.remove(datasourceId);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close MongoClient for datasource {}: {}", datasourceId,
                        ex.getMessage());
            }
        }
    }

    /** Close every cached client; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : clients.keySet()) {
            evict(datasourceId);
        }
    }

    private MongoClient create(DatasourceConnectionDescriptor descriptor) {
        var password = descriptor.passwordEncrypted() == null || descriptor.passwordEncrypted().isBlank()
                ? ""
                : credentials.decrypt(descriptor.passwordEncrypted());
        var uri = connectionStringFactory.build(descriptor, password, settings.toOptions());
        var clientSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .build();
        log.debug("Opening MongoClient for datasource {}", descriptor.id());
        return MongoClients.create(clientSettings);
    }

    private static String resolveDatabaseName(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            return descriptor.databaseName();
        }
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            var db = new ConnectionString(descriptor.jdbcUrlOverride().strip()).getDatabase();
            if (db != null && !db.isBlank()) {
                return db;
            }
        }
        throw new IllegalStateException(
                "MongoDB datasource " + descriptor.id() + " has no database name");
    }
}
