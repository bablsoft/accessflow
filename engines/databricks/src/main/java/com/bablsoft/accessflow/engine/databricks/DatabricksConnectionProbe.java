package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.EngineMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * Connection test for a Databricks datasource: runs {@code SELECT 1} through the Statement
 * Execution API (the same client, same short server-side {@code wait_timeout}) — the warehouse
 * analogue of the JDBC {@code SELECT 1} probe, proving the workspace URL, warehouse id, and
 * personal access token round-trip. Failures surface the verbatim API message.
 */
class DatabricksConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabricksConnectionProbe.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final DatabricksStatementClient client;
    private final CredentialDecryptor credentials;
    private final EngineMessages messages;

    DatabricksConnectionProbe(DatabricksStatementClient client, CredentialDecryptor credentials,
                              EngineMessages messages) {
        this.client = client;
        this.credentials = credentials;
        this.messages = messages;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try {
            var endpoint = DatabricksEndpoint.resolve(descriptor, messages);
            var accessToken = credentials.decrypt(descriptor.passwordEncrypted());
            client.execute(endpoint, accessToken, descriptor.databaseName(), "SELECT 1",
                    new LinkedHashMap<>(), 1, PROBE_TIMEOUT);
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (DatabricksApiException | IllegalArgumentException e) {
            log.warn("Databricks connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
