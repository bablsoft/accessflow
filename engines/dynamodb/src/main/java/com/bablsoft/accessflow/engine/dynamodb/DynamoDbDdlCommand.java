package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The DynamoDB table-management ("DDL") form: a JSON command document whose single key names the
 * control-plane operation, mirroring the Elasticsearch engine's JSON envelope. The accepted shapes
 * are {@code {"CreateTable": { … }}}, {@code {"DeleteTable": { … }}}, and
 * {@code {"UpdateTable": { … }}}, the inner object being the request body (it must carry a
 * {@code TableName}). The body is mapped to the typed AWS SDK request for the common fields
 * (key schema, attribute definitions, billing mode / provisioned throughput, and — for CreateTable —
 * global secondary indexes); exotic fields (streams, TTL, tags, SSE) are out of scope for v1.
 * Anything that is not one of the three operations, or is not valid JSON, fails closed with
 * {@link InvalidSqlException} (HTTP 422).
 */
final class DynamoDbDdlCommand {

    enum Operation {
        CREATE_TABLE, DELETE_TABLE, UPDATE_TABLE;

        static Operation fromKey(String key) {
            return switch (key) {
                case "CreateTable" -> CREATE_TABLE;
                case "DeleteTable" -> DELETE_TABLE;
                case "UpdateTable" -> UPDATE_TABLE;
                default -> null;
            };
        }
    }

    private final Operation operation;
    private final String tableName;
    private final JsonNode body;

    private DynamoDbDdlCommand(Operation operation, String tableName, JsonNode body) {
        this.operation = operation;
        this.tableName = tableName;
        this.body = body;
    }

    Operation operation() {
        return operation;
    }

    String tableName() {
        return tableName;
    }

    static DynamoDbDdlCommand parse(String json, EngineMessages messages) {
        JsonNode root;
        try {
            root = JsonNodeParser.create().parse(json);
        } catch (RuntimeException ex) {
            // JsonNodeParser raises provider-specific unchecked exceptions for malformed input.
            throw new InvalidSqlException(messages.get("error.dynamodb.invalid_json", brief(ex)));
        }
        if (!root.isObject() || root.asObject().size() != 1) {
            throw new InvalidSqlException(messages.get("error.dynamodb.unsupported_ddl",
                    "expected a single CreateTable / DeleteTable / UpdateTable command"));
        }
        var entry = root.asObject().entrySet().iterator().next();
        var operation = Operation.fromKey(entry.getKey());
        if (operation == null) {
            throw new InvalidSqlException(messages.get("error.dynamodb.unsupported_ddl",
                    entry.getKey()));
        }
        var commandBody = entry.getValue();
        if (!commandBody.isObject()) {
            throw new InvalidSqlException(messages.get("error.dynamodb.unsupported_ddl",
                    entry.getKey()));
        }
        var tableName = stringField(commandBody, "TableName");
        if (tableName == null || tableName.isBlank()) {
            throw new InvalidSqlException(messages.get("error.dynamodb.table_required"));
        }
        return new DynamoDbDdlCommand(operation, tableName, commandBody);
    }

    CreateTableRequest toCreateTable() {
        var builder = CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(attributeDefinitions(body))
                .keySchema(keySchema(field(body, "KeySchema")));
        var billingMode = stringField(body, "BillingMode");
        builder.billingMode(billingMode != null ? BillingMode.fromValue(billingMode)
                : BillingMode.PAY_PER_REQUEST);
        var throughput = provisionedThroughput(field(body, "ProvisionedThroughput"));
        if (throughput != null) {
            builder.provisionedThroughput(throughput);
        }
        var gsis = globalSecondaryIndexes(body);
        if (!gsis.isEmpty()) {
            builder.globalSecondaryIndexes(gsis);
        }
        return builder.build();
    }

    DeleteTableRequest toDeleteTable() {
        return DeleteTableRequest.builder().tableName(tableName).build();
    }

    UpdateTableRequest toUpdateTable() {
        var builder = UpdateTableRequest.builder().tableName(tableName);
        var billingMode = stringField(body, "BillingMode");
        if (billingMode != null) {
            builder.billingMode(BillingMode.fromValue(billingMode));
        }
        var throughput = provisionedThroughput(field(body, "ProvisionedThroughput"));
        if (throughput != null) {
            builder.provisionedThroughput(throughput);
        }
        var attributes = attributeDefinitions(body);
        if (!attributes.isEmpty()) {
            builder.attributeDefinitions(attributes);
        }
        return builder.build();
    }

    // ---- JSON → SDK mapping helpers ----------------------------------------------------------

    private static List<AttributeDefinition> attributeDefinitions(JsonNode body) {
        var definitions = new ArrayList<AttributeDefinition>();
        for (var element : arrayField(body, "AttributeDefinitions")) {
            var name = stringField(element, "AttributeName");
            var type = stringField(element, "AttributeType");
            if (name != null && type != null) {
                definitions.add(AttributeDefinition.builder().attributeName(name)
                        .attributeType(ScalarAttributeType.fromValue(type)).build());
            }
        }
        return definitions;
    }

    private static List<KeySchemaElement> keySchema(JsonNode keySchemaNode) {
        var elements = new ArrayList<KeySchemaElement>();
        if (keySchemaNode != null && keySchemaNode.isArray()) {
            for (var element : keySchemaNode.asArray()) {
                var name = stringField(element, "AttributeName");
                var keyType = stringField(element, "KeyType");
                if (name != null && keyType != null) {
                    elements.add(KeySchemaElement.builder().attributeName(name)
                            .keyType(KeyType.fromValue(keyType)).build());
                }
            }
        }
        return elements;
    }

    private static ProvisionedThroughput provisionedThroughput(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var read = longField(node, "ReadCapacityUnits");
        var write = longField(node, "WriteCapacityUnits");
        if (read == null || write == null) {
            return null;
        }
        return ProvisionedThroughput.builder().readCapacityUnits(read).writeCapacityUnits(write).build();
    }

    private static List<GlobalSecondaryIndex> globalSecondaryIndexes(JsonNode body) {
        var indexes = new ArrayList<GlobalSecondaryIndex>();
        for (var element : arrayField(body, "GlobalSecondaryIndexes")) {
            var name = stringField(element, "IndexName");
            if (name == null) {
                continue;
            }
            var builder = GlobalSecondaryIndex.builder().indexName(name)
                    .keySchema(keySchema(field(element, "KeySchema")))
                    .projection(projection(field(element, "Projection")));
            var throughput = provisionedThroughput(field(element, "ProvisionedThroughput"));
            if (throughput != null) {
                builder.provisionedThroughput(throughput);
            }
            indexes.add(builder.build());
        }
        return indexes;
    }

    private static Projection projection(JsonNode node) {
        var type = node != null ? stringField(node, "ProjectionType") : null;
        var builder = Projection.builder()
                .projectionType(type != null ? ProjectionType.fromValue(type) : ProjectionType.ALL);
        if (node != null) {
            var nonKey = arrayField(node, "NonKeyAttributes").stream()
                    .filter(JsonNode::isString).map(JsonNode::asString).toList();
            if (!nonKey.isEmpty()) {
                builder.nonKeyAttributes(nonKey);
            }
        }
        return builder.build();
    }

    private static JsonNode field(JsonNode object, String key) {
        return object != null && object.isObject() ? object.asObject().get(key) : null;
    }

    private static String stringField(JsonNode object, String key) {
        var node = field(object, key);
        return node != null && node.isString() ? node.asString() : null;
    }

    private static Long longField(JsonNode object, String key) {
        var node = field(object, key);
        if (node == null || !node.isNumber()) {
            return null;
        }
        try {
            return Long.parseLong(node.asNumber().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<JsonNode> arrayField(JsonNode object, String key) {
        var node = field(object, key);
        return node != null && node.isArray() ? node.asArray() : List.of();
    }

    private static String brief(RuntimeException ex) {
        var message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
