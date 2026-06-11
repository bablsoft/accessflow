package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryScanConsistency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers a schema for a Couchbase datasource: the bucket's scopes become
 * {@link DatabaseSchemaView.Schema}s and their collections {@link DatabaseSchemaView.Table}s, so
 * the ER diagram, editor autocomplete, and AI schema context work unchanged. Fields are sampled
 * with a bounded {@code SELECT t.* … LIMIT 50} per collection; the document key — surfaced in
 * SQL++ as {@code meta().id}, not a document field — is reported as the primary-key column.
 * Collections that cannot be sampled (typically no index yet) degrade to the key column alone
 * rather than failing the whole introspection. System scopes are skipped.
 */
class CouchbaseSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseSchemaIntrospector.class);
    private static final int SAMPLE_SIZE = 50;
    private static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(10);

    private final CredentialDecryptor credentials;
    private final CouchbaseConnectionStringFactory connectionStringFactory;

    CouchbaseSchemaIntrospector(CredentialDecryptor credentials,
                                CouchbaseConnectionStringFactory connectionStringFactory) {
        this.credentials = credentials;
        this.connectionStringFactory = connectionStringFactory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        Cluster cluster = null;
        try {
            cluster = CouchbaseConnectionSupport.connect(descriptor, credentials,
                    connectionStringFactory, CouchbaseConnectionSupport.ADMIN_CONNECT_TIMEOUT);
            var bucket = cluster.bucket(CouchbaseConnectionSupport.bucketName(descriptor));
            bucket.waitUntilReady(SAMPLE_TIMEOUT);
            var schemas = new ArrayList<DatabaseSchemaView.Schema>();
            for (var scope : bucket.collections().getAllScopes()) {
                if (scope.name().startsWith("_system")) {
                    continue;
                }
                var tables = new ArrayList<DatabaseSchemaView.Table>();
                for (var collection : scope.collections()) {
                    tables.add(new DatabaseSchemaView.Table(collection.name(),
                            columns(bucket, scope.name(), collection.name()), List.of()));
                }
                schemas.add(new DatabaseSchemaView.Schema(scope.name(), tables));
            }
            return new DatabaseSchemaView(schemas);
        } catch (CouchbaseException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Couchbase schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        } finally {
            if (cluster != null) {
                cluster.disconnect();
            }
        }
    }

    private List<DatabaseSchemaView.Column> columns(Bucket bucket, String scopeName,
                                                    String collectionName) {
        var fieldTypes = new LinkedHashMap<String, String>();
        try {
            var result = bucket.scope(scopeName).query(
                    "SELECT t.* FROM `" + collectionName.replace("`", "``") + "` AS t LIMIT "
                            + SAMPLE_SIZE,
                    QueryOptions.queryOptions().readonly(true).timeout(SAMPLE_TIMEOUT)
                            .scanConsistency(QueryScanConsistency.REQUEST_PLUS));
            for (var raw : result.rowsAs(byte[].class)) {
                if (CouchbaseJson.parseRow(raw) instanceof Map<?, ?> doc) {
                    for (var entry : doc.entrySet()) {
                        fieldTypes.putIfAbsent(String.valueOf(entry.getKey()),
                                CouchbaseResultMapper.jsonTypeName(entry.getValue()));
                    }
                }
            }
        } catch (CouchbaseException e) {
            // Typically "no index available" on a never-queried collection — degrade to the
            // document-key column rather than failing the whole introspection.
            log.debug("Couchbase field sampling skipped for {}.{}: {}", scopeName, collectionName,
                    e.getMessage());
        }
        var columns = new ArrayList<DatabaseSchemaView.Column>(fieldTypes.size() + 1);
        columns.add(new DatabaseSchemaView.Column("meta().id", "string", false, true));
        for (var entry : fieldTypes.entrySet()) {
            columns.add(new DatabaseSchemaView.Column(entry.getKey(), entry.getValue(), true,
                    false));
        }
        return columns;
    }
}
