package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoQueryParserTest {

    private final MongoQueryParser parser = new MongoQueryParser(
            TestMessages.of(Map.of("error.mongo.argument_required", "missing {0}")));

    // ---- shell form ----

    @Test
    void parsesShellFindWithFilter() {
        var result = parser.parse("db.users.find({ age: { $gt: 21 } })");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
        assertThat(result.hasWhereClause()).isTrue();
    }

    @Test
    void parsesShellFindWithProjectionAndChainedModifiers() {
        var command = parser.parseCommand(
                "db.orders.find({ status: 'open' }, { _id: 0 }).limit(10).sort({ createdAt: -1 }).skip(5)");
        assertThat(command.operation()).isEqualTo(MongoOperation.FIND);
        assertThat(command.collection()).isEqualTo("orders");
        assertThat(command.projection()).containsEntry("_id", 0);
        assertThat(command.limit()).isEqualTo(10);
        assertThat(command.skip()).isEqualTo(5);
        assertThat(command.sort()).containsEntry("createdAt", -1);
    }

    @Test
    void parsesGetCollectionForm() {
        var command = parser.parseCommand("db.getCollection(\"audit log\").find({})");
        assertThat(command.collection()).isEqualTo("audit log");
        assertThat(command.operation()).isEqualTo(MongoOperation.FIND);
    }

    @Test
    void parsesAggregatePipeline() {
        var command = parser.parseCommand(
                "db.sales.aggregate([{ $match: { region: 'EU' } }, { $group: { _id: '$sku', n: { $sum: 1 } } }])");
        assertThat(command.operation()).isEqualTo(MongoOperation.AGGREGATE);
        assertThat(command.pipeline()).hasSize(2);
    }

    @Test
    void parsesInsertOneAsInsert() {
        var command = parser.parseCommand("db.users.insertOne({ name: 'Ada', age: 36 })");
        assertThat(command.operation()).isEqualTo(MongoOperation.INSERT_ONE);
        assertThat(command.operation().queryType()).isEqualTo(QueryType.INSERT);
        assertThat(command.documents()).hasSize(1);
    }

    @Test
    void parsesUpdateManyAsUpdate() {
        var result = parser.parse("db.users.updateMany({ active: true }, { $set: { tier: 'gold' } })");
        assertThat(result.type()).isEqualTo(QueryType.UPDATE);
    }

    @Test
    void parsesDeleteOneAsDelete() {
        assertThat(parser.parse("db.users.deleteOne({ _id: 1 })").type()).isEqualTo(QueryType.DELETE);
    }

    @Test
    void parsesCreateIndexAsDdl() {
        var command = parser.parseCommand("db.users.createIndex({ email: 1 }, { name: 'email_idx' })");
        assertThat(command.operation().queryType()).isEqualTo(QueryType.DDL);
        assertThat(command.indexKeys()).containsEntry("email", 1);
        assertThat(command.options()).containsEntry("name", "email_idx");
    }

    @Test
    void parsesDbLevelCreateCollection() {
        var command = parser.parseCommand("db.createCollection('events')");
        assertThat(command.operation()).isEqualTo(MongoOperation.CREATE_COLLECTION);
        assertThat(command.collection()).isEqualTo("events");
    }

    @Test
    void parsesDropCollection() {
        assertThat(parser.parseCommand("db.temp.drop()").operation())
                .isEqualTo(MongoOperation.DROP_COLLECTION);
    }

    @Test
    void stripsTrailingSemicolon() {
        assertThat(parser.parse("db.users.find({});").type()).isEqualTo(QueryType.SELECT);
    }

    // ---- JSON command form ----

    @Test
    void parsesJsonFindCommand() {
        var command = parser.parseCommand(
                "{ \"find\": \"users\", \"filter\": { \"age\": { \"$gt\": 21 } }, \"limit\": 5 }");
        assertThat(command.operation()).isEqualTo(MongoOperation.FIND);
        assertThat(command.collection()).isEqualTo("users");
        assertThat(command.limit()).isEqualTo(5);
    }

    @Test
    void parsesJsonUpdateCommandNarrowsToSingleWhenNotMulti() {
        var command = parser.parseCommand(
                "{ \"update\": \"users\", \"updates\": [ { \"q\": { \"id\": 1 }, \"u\": { \"$set\": { \"x\": 2 } } } ] }");
        assertThat(command.operation()).isEqualTo(MongoOperation.UPDATE_ONE);
    }

    @Test
    void parsesJsonDeleteCommandMultiWhenLimitZero() {
        var command = parser.parseCommand(
                "{ \"delete\": \"users\", \"deletes\": [ { \"q\": { \"active\": false }, \"limit\": 0 } ] }");
        assertThat(command.operation()).isEqualTo(MongoOperation.DELETE_MANY);
    }

    @Test
    void parsesJsonInsertCommand() {
        var command = parser.parseCommand(
                "{ \"insert\": \"users\", \"documents\": [ { \"name\": \"a\" }, { \"name\": \"b\" } ] }");
        assertThat(command.operation()).isEqualTo(MongoOperation.INSERT_MANY);
        assertThat(command.documents()).hasSize(2);
    }

    // ---- rejections ----

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsUnrecognizedForm() {
        assertThatThrownBy(() -> parser.parse("SELECT 1")).isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsUnsupportedOperation() {
        assertThatThrownBy(() -> parser.parse("db.users.mapReduce({})"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsForbiddenWhereOperator() {
        assertThatThrownBy(() -> parser.parse("db.users.find({ $where: 'this.x > 1' })"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsAggregateOutStage() {
        assertThatThrownBy(() -> parser.parse("db.users.aggregate([{ $out: 'leak' }])"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("db.users.find({ not json )"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsMissingUpdateArgument() {
        assertThatThrownBy(() -> parser.parse("db.users.updateOne({ id: 1 })"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsUnsupportedModifier() {
        assertThatThrownBy(() -> parser.parse("db.users.find({}).hint({ x: 1 })"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsCreateCollectionWithChainedMethod() {
        assertThatThrownBy(() -> parser.parse("db.createCollection('x').find({})"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsMissingOperationAfterCollection() {
        assertThatThrownBy(() -> parser.parse("db.users"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsJsonCommandWithoutKnownOperationKey() {
        assertThatThrownBy(() -> parser.parse("{ \"explain\": \"users\" }"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsModifierOnNonReadOperation() {
        assertThatThrownBy(() -> parser.parse("db.users.deleteOne({ id: 1 }).limit(5)"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void parsesJsonAggregateAndCountAndDistinctAndCreate() {
        assertThat(parser.parseCommand("{ \"aggregate\": \"u\", \"pipeline\": [{ \"$count\": \"n\" }] }")
                .operation()).isEqualTo(MongoOperation.AGGREGATE);
        assertThat(parser.parseCommand("{ \"count\": \"u\", \"query\": { \"a\": 1 } }").operation())
                .isEqualTo(MongoOperation.COUNT_DOCUMENTS);
        assertThat(parser.parseCommand("{ \"distinct\": \"u\", \"key\": \"team\" }").operation())
                .isEqualTo(MongoOperation.DISTINCT);
        assertThat(parser.parseCommand("{ \"create\": \"events\" }").operation())
                .isEqualTo(MongoOperation.CREATE_COLLECTION);
        assertThat(parser.parseCommand("{ \"drop\": \"events\" }").operation())
                .isEqualTo(MongoOperation.DROP_COLLECTION);
        assertThat(parser.parseCommand(
                "{ \"createIndexes\": \"u\", \"indexes\": [{ \"key\": { \"a\": 1 }, \"name\": \"a_idx\" }] }")
                .operation()).isEqualTo(MongoOperation.CREATE_INDEX);
    }

    @Test
    void parsesShellReplaceOneAndFindOneAndUpdateAndDropIndex() {
        assertThat(parser.parseCommand("db.u.replaceOne({ a: 1 }, { a: 2 })").operation())
                .isEqualTo(MongoOperation.REPLACE_ONE);
        assertThat(parser.parseCommand("db.u.findOneAndUpdate({ a: 1 }, { $set: { b: 2 } })")
                .operation()).isEqualTo(MongoOperation.FIND_ONE_AND_UPDATE);
        assertThat(parser.parseCommand("db.u.dropIndex('a_idx')").operation())
                .isEqualTo(MongoOperation.DROP_INDEX);
    }
}
