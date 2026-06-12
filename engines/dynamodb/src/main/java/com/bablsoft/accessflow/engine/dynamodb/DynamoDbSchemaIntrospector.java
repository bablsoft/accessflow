package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers a schema for a DynamoDB datasource by listing tables, then for each table reading its key
 * schema (partition + sort key, flagged as the primary key) via {@code DescribeTable} and sampling a
 * bounded number of items via {@code Scan} to derive the remaining attribute names/types (DynamoDB
 * is schemaless beyond its keys, so this is Mongo-style sampling). Returns the same
 * {@link DatabaseSchemaView} as the JDBC path (one schema = the region, tables = DynamoDB tables,
 * columns = attributes, no foreign keys) so the ER diagram, editor autocomplete, and AI schema
 * context work unchanged.
 */
class DynamoDbSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbSchemaIntrospector.class);
    private static final int SAMPLE_SIZE = 50;

    private final DynamoDbClientFactory clientFactory;

    DynamoDbSchemaIntrospector(DynamoDbClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        try (var client = clientFactory.open(descriptor)) {
            var tables = new ArrayList<DatabaseSchemaView.Table>();
            for (var tableName : listTableNames(client)) {
                tables.add(new DatabaseSchemaView.Table(tableName, columns(client, tableName),
                        List.of()));
            }
            return new DatabaseSchemaView(List.of(
                    new DatabaseSchemaView.Schema(schemaName(descriptor), tables)));
        } catch (SdkException | IllegalArgumentException e) {
            log.warn("DynamoDB schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private static List<String> listTableNames(DynamoDbClient client) {
        var names = new ArrayList<String>();
        String start = null;
        do {
            var response = client.listTables(ListTablesRequest.builder()
                    .exclusiveStartTableName(start).build());
            names.addAll(response.tableNames());
            start = response.lastEvaluatedTableName();
        } while (start != null);
        return names;
    }

    private List<DatabaseSchemaView.Column> columns(DynamoDbClient client, String tableName) {
        TableDescription table = client.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()).table();
        var keyAttributes = new LinkedHashSet<String>();
        table.keySchema().forEach(element -> keyAttributes.add(element.attributeName()));
        var keyTypes = new LinkedHashMap<String, String>();
        table.attributeDefinitions().forEach(definition ->
                keyTypes.put(definition.attributeName(), scalarTypeName(definition.attributeTypeAsString())));

        var columns = new LinkedHashMap<String, DatabaseSchemaView.Column>();
        for (var key : keyAttributes) {
            columns.put(key, new DatabaseSchemaView.Column(key,
                    keyTypes.getOrDefault(key, "string"), false, true));
        }
        for (var entry : sampleAttributes(client, tableName).entrySet()) {
            columns.putIfAbsent(entry.getKey(), new DatabaseSchemaView.Column(entry.getKey(),
                    entry.getValue(), true, false));
        }
        return new ArrayList<>(columns.values());
    }

    private static Map<String, String> sampleAttributes(DynamoDbClient client, String tableName) {
        var attributes = new LinkedHashMap<String, String>();
        var items = client.scan(ScanRequest.builder().tableName(tableName).limit(SAMPLE_SIZE).build())
                .items();
        for (var item : items) {
            for (var entry : item.entrySet()) {
                attributes.putIfAbsent(entry.getKey(), attributeTypeName(entry.getValue()));
            }
        }
        return attributes;
    }

    private static String schemaName(DatasourceConnectionDescriptor descriptor) {
        var region = descriptor.databaseName();
        return region == null || region.isBlank() ? "dynamodb" : region.strip();
    }

    private static String scalarTypeName(String attributeType) {
        return switch (attributeType) {
            case "S" -> "string";
            case "N" -> "number";
            case "B" -> "binary";
            default -> attributeType.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String attributeTypeName(AttributeValue value) {
        return switch (value.type()) {
            case S -> "string";
            case N -> "number";
            case BOOL -> "bool";
            case M -> "map";
            case L -> "list";
            case B -> "binary";
            case SS -> "stringSet";
            case NS -> "numberSet";
            case BS -> "binarySet";
            case NUL -> "null";
            case UNKNOWN_TO_SDK_VERSION -> "string";
        };
    }
}
