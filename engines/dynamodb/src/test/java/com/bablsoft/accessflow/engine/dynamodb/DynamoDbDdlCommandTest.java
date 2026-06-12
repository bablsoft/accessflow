package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbDdlCommandTest {

    private final com.bablsoft.accessflow.core.api.EngineMessages messages = TestMessages.keyEcho();

    @Test
    void parsesAndMapsCreateTableWithKeySchemaAndGsi() {
        var json = """
                {"CreateTable": {
                    "TableName": "Music",
                    "AttributeDefinitions": [
                        {"AttributeName": "Artist", "AttributeType": "S"},
                        {"AttributeName": "Title", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                        {"AttributeName": "Artist", "KeyType": "HASH"},
                        {"AttributeName": "Title", "KeyType": "RANGE"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {"IndexName": "TitleIndex",
                         "KeySchema": [{"AttributeName": "Title", "KeyType": "HASH"}],
                         "Projection": {"ProjectionType": "ALL"}}
                    ]
                }}""";
        var command = DynamoDbDdlCommand.parse(json, messages);
        assertThat(command.operation()).isEqualTo(DynamoDbDdlCommand.Operation.CREATE_TABLE);
        assertThat(command.tableName()).isEqualTo("Music");

        var request = command.toCreateTable();
        assertThat(request.tableName()).isEqualTo("Music");
        assertThat(request.keySchema()).hasSize(2);
        assertThat(request.keySchema().get(0).attributeName()).isEqualTo("Artist");
        assertThat(request.keySchema().get(0).keyTypeAsString()).isEqualTo("HASH");
        assertThat(request.attributeDefinitions()).hasSize(2);
        // Absent BillingMode defaults to PAY_PER_REQUEST.
        assertThat(request.billingModeAsString()).isEqualTo("PAY_PER_REQUEST");
        assertThat(request.globalSecondaryIndexes()).hasSize(1);
        assertThat(request.globalSecondaryIndexes().get(0).indexName()).isEqualTo("TitleIndex");
    }

    @Test
    void parsesDeleteTable() {
        var command = DynamoDbDdlCommand.parse("{\"DeleteTable\": {\"TableName\": \"Music\"}}", messages);
        assertThat(command.operation()).isEqualTo(DynamoDbDdlCommand.Operation.DELETE_TABLE);
        assertThat(command.toDeleteTable().tableName()).isEqualTo("Music");
    }

    @Test
    void parsesUpdateTableWithProvisionedThroughput() {
        var json = """
                {"UpdateTable": {
                    "TableName": "Music",
                    "BillingMode": "PROVISIONED",
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 3}
                }}""";
        var request = DynamoDbDdlCommand.parse(json, messages).toUpdateTable();
        assertThat(request.tableName()).isEqualTo("Music");
        assertThat(request.billingModeAsString()).isEqualTo("PROVISIONED");
        assertThat(request.provisionedThroughput().readCapacityUnits()).isEqualTo(5L);
        assertThat(request.provisionedThroughput().writeCapacityUnits()).isEqualTo(3L);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> DynamoDbDdlCommand.parse("not json at all", messages))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("invalid_json");
    }

    @Test
    void rejectsUnknownOperation() {
        assertThatThrownBy(() -> DynamoDbDdlCommand.parse("{\"BogusOp\": {}}", messages))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("unsupported_ddl");
    }

    @Test
    void rejectsMultipleOperationKeys() {
        assertThatThrownBy(() -> DynamoDbDdlCommand.parse(
                "{\"CreateTable\": {}, \"DeleteTable\": {}}", messages))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("unsupported_ddl");
    }

    @Test
    void rejectsMissingTableName() {
        assertThatThrownBy(() -> DynamoDbDdlCommand.parse("{\"CreateTable\": {}}", messages))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("table_required");
    }
}
