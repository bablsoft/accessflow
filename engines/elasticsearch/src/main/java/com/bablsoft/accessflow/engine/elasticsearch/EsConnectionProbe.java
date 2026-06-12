package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Connection test for an Elasticsearch / OpenSearch datasource: opens a short-lived REST client and
 * issues {@code GET /} (the cluster info endpoint). The search-engine analogue of the JDBC
 * {@code SELECT 1} probe.
 */
class EsConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(EsConnectionProbe.class);

    private final SearchTransportFactory factory;

    EsConnectionProbe(SearchTransportFactory factory) {
        this.factory = factory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var transport = factory.createAdmin(descriptor)) {
            transport.perform("GET", "/", Map.of(), null, "application/json");
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (RuntimeException e) {
            log.warn("Search connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
