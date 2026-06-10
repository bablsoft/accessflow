package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.proxy.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutionResult;
import com.bablsoft.accessflow.proxy.api.UpdateExecutionResult;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import com.mongodb.client.model.IndexOptions;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes a MongoDB query for a {@link DatasourceConnectionDescriptor#dbType()} of
 * {@code MONGODB} — the document-engine analogue of {@code DefaultQueryExecutor}'s JDBC path.
 * Re-parses the submitted query, applies row-security predicates, runs the operation through the
 * native driver (read operations capped at {@code maxRows + 1} to detect truncation, bounded by a
 * per-operation {@code maxTime}), and maps the result through {@link MongoResultMapper} (which
 * applies column masking). Returns a {@code SelectExecutionResult} for reads and an
 * {@code UpdateExecutionResult} for writes/DDL.
 */
@Component
@RequiredArgsConstructor
public class MongoQueryExecutor {

    private final MongoClientManager clientManager;
    private final MongoQueryParser parser;
    private final MongoRowSecurityApplier rowSecurityApplier;
    private final MongoResultMapper resultMapper;
    private final MongoExceptionTranslator exceptionTranslator;
    private final Clock clock;

    public QueryExecutionResult execute(QueryExecutionRequest request,
                                        DatasourceConnectionDescriptor descriptor, int maxRows,
                                        Duration timeout) {
        var start = clock.instant();
        var parsed = parser.parseCommand(request.sql());
        var applied = rowSecurityApplier.apply(parsed, request.rowSecurityPredicates());
        var command = applied.command();
        var policyIds = applied.appliedPolicyIds();
        boolean read = command.operation().isRead();
        var database = clientManager.database(descriptor, read);
        try {
            if (read) {
                var docs = runRead(database, command, maxRows, timeout);
                var result = resultMapper.materialize(docs, maxRows, durationSince(start),
                        request.restrictedColumns(), request.columnMasks());
                return policyIds.isEmpty() ? result : result.withRowSecurityPolicyIds(policyIds);
            }
            long affected = runWrite(database, command);
            return new UpdateExecutionResult(affected, durationSince(start), policyIds);
        } catch (MongoException ex) {
            throw exceptionTranslator.translate(ex, timeout, LocaleContextHolder.getLocale());
        }
    }

    private List<Document> runRead(MongoDatabase database, MongoCommand command, int maxRows,
                                   Duration timeout) {
        var collection = database.getCollection(command.collection());
        long maxTimeMs = Math.max(1, timeout.toMillis());
        int fetchLimit = command.limit() != null
                ? Math.min(command.limit(), maxRows + 1)
                : maxRows + 1;
        return switch (command.operation()) {
            case FIND -> runFind(collection, command, fetchLimit, maxTimeMs);
            case AGGREGATE -> drain(collection.aggregate(command.pipeline())
                    .maxTime(maxTimeMs, TimeUnit.MILLISECONDS), maxRows + 1);
            case COUNT_DOCUMENTS -> List.of(new Document("count",
                    collection.countDocuments(filterOrEmpty(command.filter()))));
            case DISTINCT -> distinct(database, command, maxRows + 1);
            default -> throw new IllegalStateException(
                    "Non-read operation routed to runRead: " + command.operation());
        };
    }

    private List<Document> runFind(MongoCollection<Document> collection, MongoCommand command,
                                   int fetchLimit, long maxTimeMs) {
        FindIterable<Document> find = collection.find(filterOrEmpty(command.filter()))
                .maxTime(maxTimeMs, TimeUnit.MILLISECONDS)
                .limit(fetchLimit);
        if (command.projection() != null) {
            find = find.projection(command.projection());
        }
        if (command.sort() != null) {
            find = find.sort(command.sort());
        }
        if (command.skip() != null) {
            find = find.skip(command.skip());
        }
        return drain(find, fetchLimit);
    }

    private List<Document> distinct(MongoDatabase database, MongoCommand command, int cap) {
        // Run as a command so the driver returns already-decoded native values (a typed
        // distinct(..., Object.class) has no codec for the heterogeneous result element type).
        var response = database.runCommand(new Document("distinct", command.collection())
                .append("key", command.distinctKey())
                .append("query", filterOrEmpty(command.filter())));
        var out = new ArrayList<Document>();
        for (var value : response.getList("values", Object.class, List.of())) {
            if (out.size() >= cap) {
                break;
            }
            out.add(new Document("value", value));
        }
        return out;
    }

    private long runWrite(MongoDatabase database, MongoCommand command) {
        var collection = database.getCollection(command.collection());
        return switch (command.operation()) {
            case INSERT_ONE -> {
                collection.insertOne(command.documents().get(0));
                yield 1;
            }
            case INSERT_MANY -> {
                collection.insertMany(command.documents());
                yield command.documents().size();
            }
            case UPDATE_ONE -> collection.updateOne(command.filter(), command.update())
                    .getMatchedCount();
            case UPDATE_MANY -> collection.updateMany(command.filter(), command.update())
                    .getMatchedCount();
            case REPLACE_ONE -> collection.replaceOne(command.filter(), command.update())
                    .getMatchedCount();
            case FIND_ONE_AND_UPDATE ->
                    collection.findOneAndUpdate(command.filter(), command.update()) != null ? 1 : 0;
            case DELETE_ONE -> collection.deleteOne(command.filter()).getDeletedCount();
            case DELETE_MANY -> collection.deleteMany(command.filter()).getDeletedCount();
            case CREATE_COLLECTION -> {
                database.createCollection(command.collection());
                yield 0;
            }
            case CREATE_INDEX -> {
                createIndex(collection, command);
                yield 0;
            }
            case DROP_COLLECTION -> {
                collection.drop();
                yield 0;
            }
            case DROP_INDEX -> {
                collection.dropIndex(indexName(command));
                yield 0;
            }
            default -> throw new IllegalStateException(
                    "Read operation routed to runWrite: " + command.operation());
        };
    }

    private static void createIndex(MongoCollection<Document> collection, MongoCommand command) {
        if (command.options() != null && command.options().get("name") instanceof String name
                && !name.isBlank()) {
            collection.createIndex(command.indexKeys(), new IndexOptions().name(name));
        } else {
            collection.createIndex(command.indexKeys());
        }
    }

    private static String indexName(MongoCommand command) {
        if (command.options() != null && command.options().get("name") instanceof String name) {
            return name;
        }
        throw new IllegalStateException("dropIndex requires an index name");
    }

    private static Document filterOrEmpty(Document filter) {
        return filter == null ? new Document() : filter;
    }

    private static List<Document> drain(MongoIterable<Document> iterable, int cap) {
        var out = new ArrayList<Document>();
        try (var cursor = iterable.iterator()) {
            while (cursor.hasNext() && out.size() < cap) {
                out.add(cursor.next());
            }
        }
        return out;
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
