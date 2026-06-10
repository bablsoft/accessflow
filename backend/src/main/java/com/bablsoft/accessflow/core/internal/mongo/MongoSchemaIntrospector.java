package com.bablsoft.accessflow.core.internal.mongo;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.MongoConnectionStringFactory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers a schema for a {@code MONGODB} datasource by listing its collections and sampling a bounded
 * number of documents from each to derive top-level field names and BSON types. Returns the same
 * {@link DatabaseSchemaView} as the JDBC path (schema = database, tables = collections, columns =
 * fields, no foreign keys) so the ER diagram, editor autocomplete, and AI schema context work
 * unchanged. {@code _id} is reported as the primary key. System collections are skipped.
 */
@Component
@RequiredArgsConstructor
public class MongoSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaIntrospector.class);
    private static final int SAMPLE_SIZE = 50;

    private final CredentialEncryptionService encryptionService;
    private final MongoConnectionStringFactory connectionStringFactory;

    public DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        var databaseName = MongoConnectionSupport.databaseName(descriptor);
        try (var client = MongoConnectionSupport.openClient(descriptor, encryptionService,
                connectionStringFactory)) {
            var database = client.getDatabase(databaseName);
            var tables = new ArrayList<DatabaseSchemaView.Table>();
            for (var collectionName : database.listCollectionNames()) {
                if (collectionName.startsWith("system.")) {
                    continue;
                }
                tables.add(new DatabaseSchemaView.Table(collectionName,
                        sampleColumns(database.getCollection(collectionName)), List.of()));
            }
            return new DatabaseSchemaView(
                    List.of(new DatabaseSchemaView.Schema(databaseName, tables)));
        } catch (MongoException | IllegalArgumentException e) {
            log.warn("MongoDB schema introspection failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private List<DatabaseSchemaView.Column> sampleColumns(MongoCollection<Document> collection) {
        var fieldTypes = new LinkedHashMap<String, String>();
        try (var cursor = collection.find().limit(SAMPLE_SIZE).iterator()) {
            while (cursor.hasNext()) {
                for (var entry : cursor.next().entrySet()) {
                    fieldTypes.putIfAbsent(entry.getKey(), bsonTypeName(entry.getValue()));
                }
            }
        }
        var columns = new ArrayList<DatabaseSchemaView.Column>(fieldTypes.size());
        for (var entry : fieldTypes.entrySet()) {
            columns.add(new DatabaseSchemaView.Column(entry.getKey(), entry.getValue(), true,
                    "_id".equals(entry.getKey())));
        }
        return columns;
    }

    private static String bsonTypeName(Object value) {
        return switch (value) {
            case null -> "null";
            case String ignored -> "string";
            case Integer ignored -> "int32";
            case Long ignored -> "int64";
            case Double ignored -> "double";
            case Boolean ignored -> "bool";
            case ObjectId ignored -> "objectId";
            case Decimal128 ignored -> "decimal128";
            case Date ignored -> "date";
            case Binary ignored -> "binary";
            case Document ignored -> "object";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            default -> "string";
        };
    }
}
