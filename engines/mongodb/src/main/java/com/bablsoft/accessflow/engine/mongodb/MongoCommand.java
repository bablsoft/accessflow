package com.bablsoft.accessflow.engine.mongodb;

import org.bson.Document;

import java.util.List;

/**
 * A parsed, validated MongoDB command — the engine-neutral product of {@link MongoQueryParser} for
 * both the shell ({@code db.coll.find({...})}) and JSON-command ({@code {"find":"coll",...}}) forms.
 * Immutable; {@link MongoRowSecurityApplier} produces copies with row-security predicates merged in
 * via {@link #withFilter(Document)} / {@link #withPipeline(List)}.
 */
record MongoCommand(
        String collection,
        MongoOperation operation,
        Document filter,
        List<Document> pipeline,
        List<Document> documents,
        Document update,
        Document projection,
        Document sort,
        Integer limit,
        Integer skip,
        String distinctKey,
        Document indexKeys,
        Document options) {

    MongoCommand {
        documents = documents == null ? List.of() : List.copyOf(documents);
        pipeline = pipeline == null ? List.of() : List.copyOf(pipeline);
    }

    /** Returns a copy with the find/update/delete filter replaced (used for RLS injection). */
    MongoCommand withFilter(Document newFilter) {
        return new MongoCommand(collection, operation, newFilter, pipeline, documents, update,
                projection, sort, limit, skip, distinctKey, indexKeys, options);
    }

    /** Returns a copy with the aggregate pipeline replaced (used for RLS {@code $match} prefix). */
    MongoCommand withPipeline(List<Document> newPipeline) {
        return new MongoCommand(collection, operation, filter, newPipeline, documents, update,
                projection, sort, limit, skip, distinctKey, indexKeys, options);
    }

    static Builder builder(String collection, MongoOperation operation) {
        return new Builder(collection, operation);
    }

    static final class Builder {
        private final String collection;
        private MongoOperation operation;
        private Document filter;
        private List<Document> pipeline;
        private List<Document> documents;
        private Document update;
        private Document projection;
        private Document sort;
        private Integer limit;
        private Integer skip;
        private String distinctKey;
        private Document indexKeys;
        private Document options;

        private Builder(String collection, MongoOperation operation) {
            this.collection = collection;
            this.operation = operation;
        }

        /** Narrow a *_MANY operation to its *_ONE variant (JSON command form: multi/limit). */
        Builder narrowOperation(MongoOperation v) {
            this.operation = v;
            return this;
        }

        Builder filter(Document v) {
            this.filter = v;
            return this;
        }

        Builder pipeline(List<Document> v) {
            this.pipeline = v;
            return this;
        }

        Builder documents(List<Document> v) {
            this.documents = v;
            return this;
        }

        Builder update(Document v) {
            this.update = v;
            return this;
        }

        Builder projection(Document v) {
            this.projection = v;
            return this;
        }

        Builder sort(Document v) {
            this.sort = v;
            return this;
        }

        Builder limit(Integer v) {
            this.limit = v;
            return this;
        }

        Builder skip(Integer v) {
            this.skip = v;
            return this;
        }

        Builder distinctKey(String v) {
            this.distinctKey = v;
            return this;
        }

        Builder indexKeys(Document v) {
            this.indexKeys = v;
            return this;
        }

        Builder options(Document v) {
            this.options = v;
            return this;
        }

        MongoCommand build() {
            return new MongoCommand(collection, operation, filter, pipeline, documents, update,
                    projection, sort, limit, skip, distinctKey, indexKeys, options);
        }
    }
}
