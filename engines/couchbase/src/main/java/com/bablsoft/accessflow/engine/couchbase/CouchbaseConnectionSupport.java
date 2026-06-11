package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;

import java.time.Duration;

/**
 * Shared connection helpers: builds a {@link Cluster} from a datasource descriptor using the
 * shared {@link CouchbaseConnectionStringFactory}. The query path caches the cluster
 * ({@link CouchbaseClusterManager}); the admin paths (connection test + schema introspection)
 * open a short-lived one with a tight connect timeout so a misconfigured datasource fails fast in
 * the UI. The password is decrypted via the host's {@link CredentialDecryptor} at connect time
 * only — never retained.
 */
final class CouchbaseConnectionSupport {

    static final Duration ADMIN_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private CouchbaseConnectionSupport() {
    }

    static Cluster connect(DatasourceConnectionDescriptor descriptor,
                           CredentialDecryptor credentials,
                           CouchbaseConnectionStringFactory connectionStringFactory,
                           Duration connectTimeout) {
        var password = descriptor.passwordEncrypted() == null
                || descriptor.passwordEncrypted().isBlank()
                ? ""
                : credentials.decrypt(descriptor.passwordEncrypted());
        var username = descriptor.username() == null ? "" : descriptor.username();
        var spec = connectionStringFactory.build(descriptor);
        return Cluster.connect(spec.connectionString(),
                ClusterOptions.clusterOptions(username, password)
                        .environment(env -> {
                            env.timeoutConfig(timeouts -> timeouts.connectTimeout(connectTimeout));
                            env.securityConfig(spec.security());
                        }));
    }

    /** The datasource's bucket — the {@code database_name} field for Couchbase datasources. */
    static String bucketName(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.databaseName() != null && !descriptor.databaseName().isBlank()) {
            return descriptor.databaseName();
        }
        throw new IllegalStateException(
                "Couchbase datasource " + descriptor.id() + " has no bucket (database name)");
    }
}
