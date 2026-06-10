package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoRowSecurityApplierTest {

    private final MongoQueryParser parser = new MongoQueryParser(TestMessages.keyEcho());
    private final MongoRowSecurityApplier applier =
            new MongoRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective eq(String collection, String field, Object value) {
        return new RowSecurityDirective(UUID.randomUUID(), collection, field,
                RowSecurityOperator.EQUALS, List.of(value));
    }

    @Test
    void leavesCommandUnchangedWhenNoDirectiveMatchesCollection() {
        var command = parser.parseCommand("db.users.find({})");
        var applied = applier.apply(command, List.of(eq("orders", "tenant", 7)));
        assertThat(applied.command()).isSameAs(command);
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void mergesEqualsFilterIntoFindAndRecordsPolicy() {
        var command = parser.parseCommand("db.users.find({ active: true })");
        var directive = eq("users", "tenant", 7);
        var applied = applier.apply(command, List.of(directive));
        assertThat(applied.command().filter())
                .isEqualTo(new Document("$and", List.of(new Document("active", true),
                        new Document("tenant", 7))));
        assertThat(applied.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void usesRlsFilterDirectlyWhenNoExistingFilter() {
        var command = parser.parseCommand("db.users.find({})");
        var applied = applier.apply(command, List.of(eq("users", "tenant", 7)));
        assertThat(applied.command().filter()).isEqualTo(new Document("tenant", 7));
    }

    @Test
    void emptyValueListProducesDenyAll() {
        var command = parser.parseCommand("db.users.find({})");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "tenant",
                RowSecurityOperator.IN, List.of());
        var applied = applier.apply(command, List.of(directive));
        assertThat(applied.command().filter())
                .isEqualTo(new Document("tenant", new Document("$exists", false)));
    }

    @Test
    void inOperatorUsesInDocument() {
        var command = parser.parseCommand("db.users.find({})");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "users", "team",
                RowSecurityOperator.IN, List.of("a", "b"));
        var applied = applier.apply(command, List.of(directive));
        assertThat(applied.command().filter())
                .isEqualTo(new Document("team", new Document("$in", List.of("a", "b"))));
    }

    @Test
    void prependsMatchStageToAggregatePipeline() {
        var command = parser.parseCommand("db.users.aggregate([{ $group: { _id: '$t' } }])");
        var applied = applier.apply(command, List.of(eq("users", "tenant", 7)));
        assertThat(applied.command().pipeline()).hasSize(2);
        assertThat(applied.command().pipeline().get(0))
                .isEqualTo(new Document("$match", new Document("tenant", 7)));
    }

    @Test
    void mergesFilterIntoUpdate() {
        var command = parser.parseCommand("db.users.updateMany({ active: true }, { $set: { x: 1 } })");
        var applied = applier.apply(command, List.of(eq("users", "tenant", 7)));
        assertThat(applied.command().filter()).containsKey("$and");
    }

    @Test
    void rejectsInsertIntoPoliciedCollection() {
        var command = parser.parseCommand("db.users.insertOne({ name: 'x' })");
        assertThatThrownBy(() -> applier.apply(command, List.of(eq("users", "tenant", 7))))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void matchesCollectionIgnoringSchemaPrefixAndCase() {
        assertThat(MongoRowSecurityApplier.matchesCollection("MyDb.Users", "users")).isTrue();
        assertThat(MongoRowSecurityApplier.matchesCollection("users", "users")).isTrue();
        assertThat(MongoRowSecurityApplier.matchesCollection("orders", "users")).isFalse();
        assertThat(MongoRowSecurityApplier.matchesCollection(null, "users")).isFalse();
    }
}
