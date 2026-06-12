package com.bablsoft.accessflow.engine.elasticsearch;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The engine-neutral parsed form of an AccessFlow Elasticsearch / OpenSearch query envelope —
 * the analogue of the Mongo engine's {@code MongoCommand}. Carries the {@link EsOperation}, the
 * target {@code index} (name or pattern), and the operation-specific payload. Only the fields
 * relevant to {@link #operation()} are populated; the rest are {@code null} / empty. {@link
 * #withQuery(JsonNode)} returns a copy with the query replaced — used by the row-security applier
 * to splice the {@code bool.filter} wrapper without mutating the original.
 */
record EsCommand(EsOperation operation,
                 String index,
                 JsonNode query,
                 Integer size,
                 Integer from,
                 JsonNode sort,
                 JsonNode source,
                 JsonNode document,
                 String docId,
                 List<EsBulkItem> bulkItems,
                 JsonNode mappings,
                 JsonNode settings,
                 JsonNode properties) {

    EsCommand {
        bulkItems = bulkItems == null ? List.of() : List.copyOf(bulkItems);
    }

    EsCommand withQuery(JsonNode newQuery) {
        return new EsCommand(operation, index, newQuery, size, from, sort, source, document, docId,
                bulkItems, mappings, settings, properties);
    }

    static Builder builder(EsOperation operation, String index) {
        return new Builder(operation, index);
    }

    static final class Builder {

        private final EsOperation operation;
        private final String index;
        private JsonNode query;
        private Integer size;
        private Integer from;
        private JsonNode sort;
        private JsonNode source;
        private JsonNode document;
        private String docId;
        private List<EsBulkItem> bulkItems = List.of();
        private JsonNode mappings;
        private JsonNode settings;
        private JsonNode properties;

        private Builder(EsOperation operation, String index) {
            this.operation = operation;
            this.index = index;
        }

        Builder query(JsonNode query) {
            this.query = query;
            return this;
        }

        Builder size(Integer size) {
            this.size = size;
            return this;
        }

        Builder from(Integer from) {
            this.from = from;
            return this;
        }

        Builder sort(JsonNode sort) {
            this.sort = sort;
            return this;
        }

        Builder source(JsonNode source) {
            this.source = source;
            return this;
        }

        Builder document(JsonNode document) {
            this.document = document;
            return this;
        }

        Builder docId(String docId) {
            this.docId = docId;
            return this;
        }

        Builder bulkItems(List<EsBulkItem> bulkItems) {
            this.bulkItems = bulkItems;
            return this;
        }

        Builder mappings(JsonNode mappings) {
            this.mappings = mappings;
            return this;
        }

        Builder settings(JsonNode settings) {
            this.settings = settings;
            return this;
        }

        Builder properties(JsonNode properties) {
            this.properties = properties;
            return this;
        }

        EsCommand build() {
            return new EsCommand(operation, index, query, size, from, sort, source, document, docId,
                    bulkItems, mappings, settings, properties);
        }
    }
}
