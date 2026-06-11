package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Connection test for a Couchbase datasource: opens a short-lived cluster, waits for the bucket
 * to be ready, and issues a {@code SELECT RAW 1} through the query service — the SQL++ analogue
 * of the JDBC {@code SELECT 1} probe (proving KV bootstrap alone would not prove that SQL++
 * queries can run).
 */
class CouchbaseConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseConnectionProbe.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final CredentialDecryptor credentials;
    private final CouchbaseConnectionStringFactory connectionStringFactory;

    CouchbaseConnectionProbe(CredentialDecryptor credentials,
                             CouchbaseConnectionStringFactory connectionStringFactory) {
        this.credentials = credentials;
        this.connectionStringFactory = connectionStringFactory;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        Cluster cluster = null;
        try {
            cluster = CouchbaseConnectionSupport.connect(descriptor, credentials,
                    connectionStringFactory, CouchbaseConnectionSupport.ADMIN_CONNECT_TIMEOUT);
            cluster.bucket(CouchbaseConnectionSupport.bucketName(descriptor))
                    .waitUntilReady(PROBE_TIMEOUT);
            cluster.query("SELECT RAW 1",
                    QueryOptions.queryOptions().readonly(true).timeout(PROBE_TIMEOUT));
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (CouchbaseException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Couchbase connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        } finally {
            disconnectQuietly(cluster);
        }
    }

    private static void disconnectQuietly(Cluster cluster) {
        if (cluster != null) {
            try {
                cluster.disconnect();
            } catch (RuntimeException e) {
                log.debug("Couchbase probe disconnect failed: {}", e.getMessage());
            }
        }
    }
}
