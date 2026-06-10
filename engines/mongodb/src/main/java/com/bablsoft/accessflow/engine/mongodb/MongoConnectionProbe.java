package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.mongodb.MongoException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection test for a MongoDB datasource: opens a short-lived client and issues a {@code ping}
 * command. The document-engine analogue of the JDBC {@code SELECT 1} probe.
 */
class MongoConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(MongoConnectionProbe.class);

    private final CredentialDecryptor credentials;
    private final MongoConnectionStringFactory connectionStringFactory;

    MongoConnectionProbe(CredentialDecryptor credentials,
                         MongoConnectionStringFactory connectionStringFactory) {
        this.credentials = credentials;
        this.connectionStringFactory = connectionStringFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var client = MongoConnectionSupport.openClient(descriptor, credentials,
                connectionStringFactory)) {
            client.getDatabase(MongoConnectionSupport.databaseName(descriptor))
                    .runCommand(new Document("ping", 1));
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (MongoException | IllegalArgumentException e) {
            log.warn("MongoDB connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
