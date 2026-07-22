package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection test for a BigQuery datasource: opens a short-lived client and lists the project's
 * datasets capped at one page entry — the BigQuery analogue of the JDBC {@code SELECT 1} probe
 * (proving the endpoint, project id, and credentials round-trip without creating a billable query
 * job). Works against both the real API and the emulator. Invalid service-account key JSON
 * surfaces here as an {@link IllegalArgumentException} from the client factory.
 */
class BigQueryConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(BigQueryConnectionProbe.class);

    private final BigQueryClientFactory clientFactory;

    BigQueryConnectionProbe(BigQueryClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try {
            var client = clientFactory.open(descriptor);
            var project = BigQueryClientFactory.ProjectTarget.parse(descriptor.databaseName());
            client.listDatasets(project.projectId(), BigQuery.DatasetListOption.pageSize(1));
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (BigQueryException | IllegalArgumentException e) {
            log.warn("BigQuery connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
