package com.bablsoft.accessflow.core.internal.mongo;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.MongoConnectionStringFactory;
import com.mongodb.MongoException;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Connection test for a {@code MONGODB} datasource: opens a short-lived client and issues a
 * {@code ping} command. The document-engine analogue of the JDBC {@code SELECT 1} probe.
 */
@Component
@RequiredArgsConstructor
public class MongoConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(MongoConnectionProbe.class);

    private final CredentialEncryptionService encryptionService;
    private final MongoConnectionStringFactory connectionStringFactory;

    public ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var client = MongoConnectionSupport.openClient(descriptor, encryptionService,
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
