package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection test for a DynamoDB datasource: opens a short-lived {@link
 * software.amazon.awssdk.services.dynamodb.DynamoDbClient} and issues {@code ListTables} (limit 1) —
 * the DynamoDB analogue of the JDBC {@code SELECT 1} probe (proving the endpoint, region, and
 * credentials round-trip).
 */
class DynamoDbConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConnectionProbe.class);

    private final DynamoDbClientFactory clientFactory;

    DynamoDbConnectionProbe(DynamoDbClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var client = clientFactory.open(descriptor)) {
            client.listTables(ListTablesRequest.builder().limit(1).build());
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (SdkException | IllegalArgumentException e) {
            log.warn("DynamoDB connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
