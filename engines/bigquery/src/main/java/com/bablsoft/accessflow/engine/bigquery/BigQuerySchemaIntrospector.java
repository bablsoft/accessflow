package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Introspects a BigQuery datasource into the engine-neutral {@link DatabaseSchemaView}: datasets
 * map to schemas and tables/views to tables. When the datasource's {@code database_name} pins a
 * default dataset ({@code project.dataset}), only that dataset is introspected; otherwise every
 * dataset in the project is listed. Table schemas come from {@code tables.get} (the list call
 * returns no field metadata); RECORD fields are flattened into dot-path columns
 * ({@code parent.child}) so masking policies and the ER diagram address nested leaves directly,
 * with REPEATED mode noted in the type name ({@code ARRAY<…>}). BigQuery has no primary keys, so
 * no column carries the PK flag.
 */
class BigQuerySchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(BigQuerySchemaIntrospector.class);

    private final BigQueryClientFactory clientFactory;

    BigQuerySchemaIntrospector(BigQueryClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try {
            var client = clientFactory.open(descriptor);
            var target = BigQueryClientFactory.ProjectTarget.parse(descriptor.databaseName());
            var schemas = new ArrayList<DatabaseSchemaView.Schema>();
            for (var dataset : datasetNames(client, target)) {
                schemas.add(new DatabaseSchemaView.Schema(dataset,
                        tables(client, target.projectId(), dataset)));
            }
            return new DatabaseSchemaView(schemas);
        } catch (BigQueryException | IllegalArgumentException e) {
            log.warn("BigQuery schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static List<String> datasetNames(BigQuery client,
                                             BigQueryClientFactory.ProjectTarget target) {
        if (target.defaultDataset() != null && !target.defaultDataset().isBlank()) {
            return List.of(target.defaultDataset());
        }
        var names = new ArrayList<String>();
        client.listDatasets(target.projectId()).iterateAll()
                .forEach(dataset -> names.add(dataset.getDatasetId().getDataset()));
        return names;
    }

    private static List<DatabaseSchemaView.Table> tables(BigQuery client, String projectId,
                                                         String dataset) {
        var tables = new ArrayList<DatabaseSchemaView.Table>();
        for (var listed : client.listTables(DatasetId.of(projectId, dataset)).iterateAll()) {
            tables.add(new DatabaseSchemaView.Table(listed.getTableId().getTable(),
                    columns(client, TableId.of(projectId, dataset, listed.getTableId().getTable())),
                    List.of()));
        }
        return tables;
    }

    private static List<DatabaseSchemaView.Column> columns(BigQuery client, TableId tableId) {
        var table = client.getTable(tableId);
        if (table == null) {
            return List.of();
        }
        Schema schema = table.getDefinition().getSchema();
        if (schema == null) {
            return List.of();
        }
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        for (var field : schema.getFields()) {
            flatten("", field, columns);
        }
        return columns;
    }

    /** Flattens RECORD fields into dot-path leaf columns; leaves carry their GoogleSQL type. */
    private static void flatten(String prefix, Field field,
                                List<DatabaseSchemaView.Column> columns) {
        var name = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
        var legacy = field.getType();
        if (legacy != null && legacy.getStandardType() == StandardSQLTypeName.STRUCT) {
            for (var sub : field.getSubFields()) {
                flatten(name, sub, columns);
            }
            return;
        }
        columns.add(new DatabaseSchemaView.Column(name, BigQueryResultMapper.typeName(field),
                field.getMode() != Field.Mode.REQUIRED, false));
    }
}
