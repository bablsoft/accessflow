package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.EngineMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Introspects a Snowflake database into the engine-neutral {@link DatabaseSchemaView} via JDBC
 * {@link DatabaseMetaData}: tables and views of every schema in the datasource's database
 * (catalog), excluding {@code INFORMATION_SCHEMA}; columns with type/nullability per table; and
 * primary-key flags from {@code getPrimaryKeys}. Drives the ER diagram, editor autocomplete, and
 * AI schema context exactly like the host's JDBC path.
 */
class SnowflakeSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeSchemaIntrospector.class);
    private static final String EXCLUDED_SCHEMA = "INFORMATION_SCHEMA";

    private final SnowflakeConnectionFactory connectionFactory;
    private final EngineMessages messages;

    SnowflakeSchemaIntrospector(SnowflakeConnectionFactory connectionFactory,
                                EngineMessages messages) {
        this.connectionFactory = connectionFactory;
        this.messages = messages;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try (var connection = connectionFactory.open(descriptor)) {
            var metaData = connection.getMetaData();
            var catalog = descriptor.databaseName() == null || descriptor.databaseName().isBlank()
                    ? null
                    : descriptor.databaseName().strip();
            var bySchema = new LinkedHashMap<String, List<DatabaseSchemaView.Table>>();
            try (var tables = metaData.getTables(catalog, null, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (tables.next()) {
                    var schema = tables.getString("TABLE_SCHEM");
                    if (schema == null || EXCLUDED_SCHEMA.equalsIgnoreCase(schema)) {
                        continue;
                    }
                    var table = tables.getString("TABLE_NAME");
                    bySchema.computeIfAbsent(schema, key -> new ArrayList<>())
                            .add(new DatabaseSchemaView.Table(table,
                                    columns(metaData, catalog, schema, table), List.of()));
                }
            }
            var schemas = new ArrayList<DatabaseSchemaView.Schema>(bySchema.size());
            bySchema.forEach((name, tables) ->
                    schemas.add(new DatabaseSchemaView.Schema(name, tables)));
            return new DatabaseSchemaView(schemas);
        } catch (SQLException e) {
            log.warn("Snowflake schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        } catch (SnowflakeConfigException e) {
            log.warn("Snowflake schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.messageKey());
            throw new DatasourceConnectionTestException(messages.get(e.messageKey(), e.args()));
        }
    }

    private static List<DatabaseSchemaView.Column> columns(DatabaseMetaData metaData,
                                                           String catalog, String schema,
                                                           String table) throws SQLException {
        var primaryKeys = primaryKeys(metaData, catalog, schema, table);
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        try (var resultSet = metaData.getColumns(catalog, schema, table, "%")) {
            while (resultSet.next()) {
                var name = resultSet.getString("COLUMN_NAME");
                columns.add(new DatabaseSchemaView.Column(
                        name,
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        primaryKeys.contains(name)));
            }
        }
        return columns;
    }

    private static Set<String> primaryKeys(DatabaseMetaData metaData, String catalog,
                                           String schema, String table) throws SQLException {
        var keys = new LinkedHashSet<String>();
        try (var resultSet = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                keys.add(resultSet.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }
}
