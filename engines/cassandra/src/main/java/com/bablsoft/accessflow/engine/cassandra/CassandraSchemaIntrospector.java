package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Infers a schema for a Cassandra datasource from the driver's live cluster metadata (derived from
 * {@code system_schema.keyspaces/tables/columns}): each non-system keyspace becomes a
 * {@link DatabaseSchemaView.Schema}, its tables {@link DatabaseSchemaView.Table}s, and partition +
 * clustering key columns are flagged as the primary key — so the ER diagram, editor autocomplete,
 * and AI schema context work unchanged, and the row-security applier knows which columns it may
 * filter on. CQL has no foreign keys.
 */
class CassandraSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(CassandraSchemaIntrospector.class);
    private static final Set<String> SYSTEM_KEYSPACES = Set.of(
            "system", "system_schema", "system_auth", "system_distributed", "system_traces",
            "system_views", "system_virtual_schema");

    private final CassandraSessionFactory sessionFactory;

    CassandraSchemaIntrospector(CassandraSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try (var session = sessionFactory.open(descriptor)) {
            var schemas = new ArrayList<DatabaseSchemaView.Schema>();
            for (var keyspace : session.getMetadata().getKeyspaces().values()) {
                var name = keyspace.getName().asInternal();
                if (SYSTEM_KEYSPACES.contains(name)) {
                    continue;
                }
                var tables = new ArrayList<DatabaseSchemaView.Table>();
                for (var table : keyspace.getTables().values()) {
                    tables.add(new DatabaseSchemaView.Table(table.getName().asInternal(),
                            columns(table), List.of()));
                }
                schemas.add(new DatabaseSchemaView.Schema(name, tables));
            }
            return new DatabaseSchemaView(schemas);
        } catch (DriverException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Cassandra schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static List<DatabaseSchemaView.Column> columns(TableMetadata table) {
        var keyColumns = primaryKeyColumns(table);
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        for (var column : table.getColumns().values()) {
            var name = column.getName().asInternal();
            boolean primaryKey = keyColumns.contains(name);
            columns.add(new DatabaseSchemaView.Column(name, column.getType().asCql(false, true),
                    !primaryKey, primaryKey));
        }
        return columns;
    }

    static Set<String> primaryKeyColumns(TableMetadata table) {
        var keys = new LinkedHashSet<String>();
        for (var column : table.getPartitionKey()) {
            keys.add(column.getName().asInternal());
        }
        for (ColumnMetadata column : table.getClusteringColumns().keySet()) {
            keys.add(column.getName().asInternal());
        }
        return keys;
    }
}
