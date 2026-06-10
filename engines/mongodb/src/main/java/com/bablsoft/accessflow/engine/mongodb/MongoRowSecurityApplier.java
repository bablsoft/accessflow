package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies resolved row-security predicates to a parsed {@link MongoCommand} at parity with the SQL
 * {@code RowSecurityRewriter}: each {@link RowSecurityDirective} whose {@code tableRef} matches the
 * target collection becomes a filter fragment ({@code EQUALS → {f:v}}, {@code IN → {f:{$in:[…]}}},
 * …; an empty value list is the fail-closed deny-all). Fragments are {@code $and}-combined and:
 * <ul>
 *   <li>find / count / distinct / update / delete → merged into the operation filter;</li>
 *   <li>aggregate → prepended as a {@code $match} stage;</li>
 *   <li>insert into a policied collection → rejected (HTTP 422), since an insert cannot be filtered;</li>
 *   <li>DDL → unaffected.</li>
 * </ul>
 */
class MongoRowSecurityApplier {

    record Applied(MongoCommand command, Set<UUID> appliedPolicyIds) {
    }

    private final EngineMessages messages;

    MongoRowSecurityApplier(EngineMessages messages) {
        this.messages = messages;
    }

    Applied apply(MongoCommand command, List<RowSecurityDirective> directives) {
        var matching = new ArrayList<RowSecurityDirective>();
        if (directives != null) {
            for (var directive : directives) {
                if (matchesCollection(directive.tableRef(), command.collection())) {
                    matching.add(directive);
                }
            }
        }
        if (matching.isEmpty()) {
            return new Applied(command, Set.of());
        }
        var policyIds = new LinkedHashSet<UUID>();
        var fragments = new ArrayList<Document>(matching.size());
        for (var directive : matching) {
            fragments.add(toFragment(directive));
            policyIds.add(directive.policyId());
        }
        var rlsFilter = combine(fragments);
        var rewritten = switch (command.operation()) {
            case FIND, COUNT_DOCUMENTS, DISTINCT, UPDATE_ONE, UPDATE_MANY, REPLACE_ONE,
                    FIND_ONE_AND_UPDATE, DELETE_ONE, DELETE_MANY ->
                    command.withFilter(and(command.filter(), rlsFilter));
            case AGGREGATE -> command.withPipeline(prependMatch(command.pipeline(), rlsFilter));
            case INSERT_ONE, INSERT_MANY -> throw new UnrewritableRowSecurityException(
                    messages.get("error.row_security_mongo_insert_unsupported",
                            command.collection()));
            case CREATE_COLLECTION, CREATE_INDEX, DROP_COLLECTION, DROP_INDEX -> command;
        };
        return new Applied(rewritten, policyIds);
    }

    private static Document toFragment(RowSecurityDirective directive) {
        var field = directive.columnName();
        var values = directive.values();
        if (values.isEmpty()) {
            // Fail-closed: a predicate no document can satisfy (every document has an _id).
            return new Document(field, new Document("$exists", false));
        }
        var first = values.get(0);
        return switch (directive.operator()) {
            case EQUALS -> new Document(field, first);
            case NOT_EQUALS -> new Document(field, new Document("$ne", first));
            case LESS_THAN -> new Document(field, new Document("$lt", first));
            case LESS_THAN_OR_EQUAL -> new Document(field, new Document("$lte", first));
            case GREATER_THAN -> new Document(field, new Document("$gt", first));
            case GREATER_THAN_OR_EQUAL -> new Document(field, new Document("$gte", first));
            case IN -> new Document(field, new Document("$in", values));
            case NOT_IN -> new Document(field, new Document("$nin", values));
        };
    }

    private static Document combine(List<Document> fragments) {
        return fragments.size() == 1 ? fragments.get(0) : new Document("$and", fragments);
    }

    private static Document and(Document existing, Document rls) {
        if (existing == null || existing.isEmpty()) {
            return rls;
        }
        return new Document("$and", List.of(existing, rls));
    }

    private static List<Document> prependMatch(List<Document> pipeline, Document rls) {
        var combined = new ArrayList<Document>(pipeline.size() + 1);
        combined.add(new Document("$match", rls));
        combined.addAll(pipeline);
        return combined;
    }

    /**
     * A directive's {@code tableRef} matches the collection when the last dot-segment (e.g.
     * {@code "mydb.users" → "users"}) equals the collection name, case-insensitively.
     */
    static boolean matchesCollection(String tableRef, String collection) {
        if (tableRef == null || collection == null) {
            return false;
        }
        var ref = tableRef.toLowerCase(Locale.ROOT).trim();
        int dot = ref.lastIndexOf('.');
        var refCollection = dot >= 0 ? ref.substring(dot + 1) : ref;
        return refCollection.equals(collection.toLowerCase(Locale.ROOT).trim());
    }
}
