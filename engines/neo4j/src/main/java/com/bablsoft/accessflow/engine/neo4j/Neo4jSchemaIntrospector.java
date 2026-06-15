package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers a schema for a Neo4j datasource from the server's own schema-sampling procedures
 * ({@code db.schema.nodeTypeProperties()} / {@code db.schema.relTypeProperties()} — both in the
 * engine's read-only procedure allow-list). Each node label becomes a
 * {@link DatabaseSchemaView.Table}, its sampled property keys the columns, and a synthetic
 * {@code _elementId} column the primary key (Neo4j's node identity); relationship types are surfaced
 * as additional tables so the allow-list and the ER diagram see the whole graph shape. Graph schema
 * has no foreign keys. All tables sit under one schema named for the datasource database.
 */
class Neo4jSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaIntrospector.class);
    private static final String ELEMENT_ID = "_elementId";

    private final Neo4jDriverFactory driverFactory;

    Neo4jSchemaIntrospector(Neo4jDriverFactory driverFactory) {
        this.driverFactory = driverFactory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try (var driver = driverFactory.open(descriptor);
             var session = driver.session(Neo4jConnectionProbe.sessionConfig(descriptor))) {
            var tables = new LinkedHashMap<String, Map<String, String>>();
            collectNodeTypes(session, tables);
            collectRelationshipTypes(session, tables);
            var schemaName = descriptor.databaseName() != null && !descriptor.databaseName().isBlank()
                    ? descriptor.databaseName().strip() : "neo4j";
            return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema(schemaName,
                    toTables(tables))));
        } catch (Neo4jException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Neo4j schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static void collectNodeTypes(org.neo4j.driver.Session session,
                                         Map<String, Map<String, String>> tables) {
        var result = session.run("CALL db.schema.nodeTypeProperties()");
        while (result.hasNext()) {
            var record = result.next();
            var labels = record.get("nodeLabels").asList(Values.ofString(), List.of());
            var property = record.get("propertyName").isNull() ? null
                    : record.get("propertyName").asString();
            var type = record.get("propertyTypes").asList(Values.ofString(), List.of());
            for (var label : labels) {
                var columns = tables.computeIfAbsent(label, k -> new LinkedHashMap<>());
                if (property != null) {
                    columns.put(property, type.isEmpty() ? "any" : type.get(0));
                }
            }
        }
    }

    private static void collectRelationshipTypes(org.neo4j.driver.Session session,
                                                 Map<String, Map<String, String>> tables) {
        var result = session.run("CALL db.schema.relTypeProperties()");
        while (result.hasNext()) {
            var record = result.next();
            var relType = stripRelType(record.get("relType").asString(""));
            if (relType.isEmpty()) {
                continue;
            }
            var property = record.get("propertyName").isNull() ? null
                    : record.get("propertyName").asString();
            var type = record.get("propertyTypes").asList(Values.ofString(), List.of());
            var columns = tables.computeIfAbsent(relType, k -> new LinkedHashMap<>());
            if (property != null) {
                columns.put(property, type.isEmpty() ? "any" : type.get(0));
            }
        }
    }

    /** {@code relType} arrives as {@code ":`OWNS`"}; reduce it to the bare type name. */
    private static String stripRelType(String relType) {
        return relType.replace(":", "").replace("`", "").strip();
    }

    private static List<DatabaseSchemaView.Table> toTables(Map<String, Map<String, String>> tables) {
        var out = new ArrayList<DatabaseSchemaView.Table>(tables.size());
        for (var entry : tables.entrySet()) {
            var columns = new ArrayList<DatabaseSchemaView.Column>();
            columns.add(new DatabaseSchemaView.Column(ELEMENT_ID, "String", false, true));
            for (var column : entry.getValue().entrySet()) {
                columns.add(new DatabaseSchemaView.Column(column.getKey(), column.getValue(), true, false));
            }
            out.add(new DatabaseSchemaView.Table(entry.getKey(), columns, List.of()));
        }
        return out;
    }
}
