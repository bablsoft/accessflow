package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.EngineMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Introspects a Databricks datasource through plain {@code information_schema} queries run over
 * the same Statement Execution API client as everything else. When the datasource pins a Unity
 * Catalog catalog ({@code database_name}), the queries are catalog-qualified
 * ({@code `<catalog>`.information_schema.…}); otherwise they run unqualified so the warehouse's
 * default catalog applies. Tables are grouped by {@code table_schema} into the engine-neutral
 * {@link DatabaseSchemaView} (no primary-key flags in v1 — {@code information_schema} PK
 * constraint metadata is not consistently populated across warehouse types), so the ER diagram,
 * editor autocomplete, and AI schema context work unchanged.
 */
class DatabricksSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(DatabricksSchemaIntrospector.class);
    private static final Duration INTROSPECTION_TIMEOUT = Duration.ofSeconds(60);

    private final DatabricksStatementClient client;
    private final CredentialDecryptor credentials;
    private final EngineMessages messages;

    DatabricksSchemaIntrospector(DatabricksStatementClient client, CredentialDecryptor credentials,
                                 EngineMessages messages) {
        this.client = client;
        this.credentials = credentials;
        this.messages = messages;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try {
            var endpoint = DatabricksEndpoint.resolve(descriptor, messages);
            var accessToken = credentials.decrypt(descriptor.passwordEncrypted());
            var prefix = qualifier(descriptor.databaseName());
            var tables = client.execute(endpoint, accessToken, descriptor.databaseName(),
                    "SELECT table_schema, table_name FROM " + prefix + "information_schema.tables"
                            + " WHERE table_schema <> 'information_schema'"
                            + " ORDER BY table_schema, table_name",
                    new LinkedHashMap<>(), null, INTROSPECTION_TIMEOUT);
            var columns = client.execute(endpoint, accessToken, descriptor.databaseName(),
                    "SELECT table_schema, table_name, column_name, data_type, is_nullable"
                            + " FROM " + prefix + "information_schema.columns"
                            + " WHERE table_schema <> 'information_schema'"
                            + " ORDER BY table_schema, table_name, ordinal_position",
                    new LinkedHashMap<>(), null, INTROSPECTION_TIMEOUT);
            return assemble(tables.rows(), columns.rows());
        } catch (DatabricksApiException | IllegalArgumentException e) {
            log.warn("Databricks schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static String qualifier(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return "";
        }
        return "`" + catalog.strip().replace("`", "") + "`.";
    }

    private static DatabaseSchemaView assemble(List<List<String>> tableRows,
                                               List<List<String>> columnRows) {
        // schema -> table -> columns, in server order.
        var schemas = new LinkedHashMap<String, LinkedHashMap<String, List<DatabaseSchemaView.Column>>>();
        for (var row : tableRows) {
            if (row.size() < 2 || row.get(0) == null || row.get(1) == null) {
                continue;
            }
            schemas.computeIfAbsent(row.get(0), s -> new LinkedHashMap<>())
                    .computeIfAbsent(row.get(1), t -> new ArrayList<>());
        }
        for (var row : columnRows) {
            if (row.size() < 5 || row.get(0) == null || row.get(1) == null || row.get(2) == null) {
                continue;
            }
            var tables = schemas.get(row.get(0));
            if (tables == null) {
                continue;
            }
            var columns = tables.get(row.get(1));
            if (columns == null) {
                continue;
            }
            columns.add(new DatabaseSchemaView.Column(row.get(2),
                    row.get(3) == null ? "" : row.get(3), "YES".equalsIgnoreCase(row.get(4)),
                    false));
        }
        var schemaViews = new ArrayList<DatabaseSchemaView.Schema>(schemas.size());
        for (var schemaEntry : schemas.entrySet()) {
            var tableViews = new ArrayList<DatabaseSchemaView.Table>(schemaEntry.getValue().size());
            for (var tableEntry : schemaEntry.getValue().entrySet()) {
                tableViews.add(new DatabaseSchemaView.Table(tableEntry.getKey(),
                        tableEntry.getValue(), List.of()));
            }
            schemaViews.add(new DatabaseSchemaView.Schema(schemaEntry.getKey(), tableViews));
        }
        return new DatabaseSchemaView(schemaViews);
    }
}
