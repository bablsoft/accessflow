package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link Cluster} per Couchbase datasource (the SDK pools connections
 * internally), the document-engine analogue of the host's JDBC connection-pool manager. Clusters
 * are evicted when the host calls {@code QueryEngine.evictDatasource} on datasource config-change
 * / deactivation. Every query runs through the datasource bucket's <em>default scope</em>, which
 * sets the SQL++ {@code query_context}: bare collection names resolve inside the configured
 * bucket (never an arbitrary cluster bucket), while fully-qualified
 * {@code bucket.scope.collection} paths still work.
 */
class CouchbaseClusterManager {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseClusterManager.class);

    private final CredentialDecryptor credentials;
    private final CouchbaseConnectionStringFactory connectionStringFactory;
    private final CouchbaseEngineSettings settings;
    private final Map<UUID, Cluster> clusters = new ConcurrentHashMap<>();

    CouchbaseClusterManager(CredentialDecryptor credentials,
                            CouchbaseConnectionStringFactory connectionStringFactory,
                            CouchbaseEngineSettings settings) {
        this.credentials = credentials;
        this.connectionStringFactory = connectionStringFactory;
        this.settings = settings;
    }

    /** The default scope of the datasource's bucket — the query context for every statement. */
    Scope defaultScope(DatasourceConnectionDescriptor descriptor) {
        var cluster = clusters.computeIfAbsent(descriptor.id(), id -> create(descriptor));
        return cluster.bucket(CouchbaseConnectionSupport.bucketName(descriptor)).defaultScope();
    }

    void evict(UUID datasourceId) {
        var cluster = clusters.remove(datasourceId);
        if (cluster != null) {
            try {
                cluster.disconnect();
            } catch (RuntimeException ex) {
                log.warn("Failed to disconnect Couchbase cluster for datasource {}: {}",
                        datasourceId, ex.getMessage());
            }
        }
    }

    /** Disconnect every cached cluster; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : clusters.keySet()) {
            evict(datasourceId);
        }
    }

    private Cluster create(DatasourceConnectionDescriptor descriptor) {
        log.debug("Opening Couchbase cluster for datasource {}", descriptor.id());
        var cluster = CouchbaseConnectionSupport.connect(descriptor, credentials,
                connectionStringFactory, settings.connectTimeout());
        // One-time per cached cluster: the bucket must be ready before the first query.
        cluster.bucket(CouchbaseConnectionSupport.bucketName(descriptor))
                .waitUntilReady(settings.waitUntilReadyTimeout());
        return cluster;
    }
}
