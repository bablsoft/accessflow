package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Infers a schema for an Elasticsearch / OpenSearch datasource from {@code GET <pattern>/_mapping}:
 * each non-system index becomes a table, each mapped field a column (flattened to dot-paths so
 * nested objects are addressable by masking / row security), and a synthetic {@code _id} keyword
 * column is reported as the primary key (the only stable identity the engines expose). Returns the
 * same {@link DatabaseSchemaView} as the JDBC path so the ER diagram, editor autocomplete, and AI
 * schema context work unchanged. Indices whose name starts with {@code .} (system indices) are
 * skipped. When {@code database_name} is set it scopes the mapping fetch to that index / pattern.
 */
class EsSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(EsSchemaIntrospector.class);

    private final SearchTransportFactory factory;

    EsSchemaIntrospector(SearchTransportFactory factory) {
        this.factory = factory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        var scope = descriptor.databaseName() != null && !descriptor.databaseName().isBlank()
                ? descriptor.databaseName().strip() : null;
        var schemaName = scope != null ? scope : "default";
        var path = scope != null ? "/" + scope + "/_mapping" : "/_mapping";
        try (var transport = factory.createAdmin(descriptor)) {
            var root = EsJson.parse(transport.perform("GET", path, Map.of(), null, "application/json"));
            var tables = new ArrayList<DatabaseSchemaView.Table>();
            for (var entry : root.properties()) {
                var indexName = entry.getKey();
                if (indexName.startsWith(".")) {
                    continue;
                }
                var columns = new ArrayList<DatabaseSchemaView.Column>();
                columns.add(new DatabaseSchemaView.Column("_id", "keyword", false, true));
                flatten(entry.getValue().path("mappings").path("properties"), "", columns);
                tables.add(new DatabaseSchemaView.Table(indexName, columns, List.of()));
            }
            return new DatabaseSchemaView(
                    List.of(new DatabaseSchemaView.Schema(schemaName, tables)));
        } catch (RuntimeException e) {
            log.warn("Search schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static void flatten(JsonNode properties, String prefix,
                                List<DatabaseSchemaView.Column> out) {
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (var entry : properties.properties()) {
            var full = prefix + entry.getKey();
            var nested = entry.getValue().path("properties");
            if (nested.isObject() && !nested.isEmpty()) {
                flatten(nested, full + ".", out);
            } else {
                var type = entry.getValue().path("type");
                out.add(new DatabaseSchemaView.Column(full,
                        type.isString() ? type.asString() : "object", true, false));
            }
        }
    }
}
