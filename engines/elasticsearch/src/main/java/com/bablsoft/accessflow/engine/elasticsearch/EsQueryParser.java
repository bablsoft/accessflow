package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates an AccessFlow Elasticsearch / OpenSearch query envelope — a single JSON
 * object whose first recognised command key names the operation and whose value is the target
 * index name / pattern — into an engine-neutral {@link EsCommand} and {@link SqlParseResult}. Reads
 * ({@code search} / {@code count} / {@code get} / {@code mget}), writes ({@code index} /
 * {@code bulk}), by-query mutations ({@code update_by_query} / {@code delete_by_query}) and index
 * management ({@code create_index} / {@code put_mapping} / {@code delete_index}) are supported;
 * {@code get} / {@code mget} are lowered to a {@code search} with an {@code ids} query so there is
 * a single row-security path. Server-side scripting, cluster-level APIs, system-index targets,
 * unknown commands, and malformed JSON are rejected with {@link InvalidSqlException} (HTTP 422),
 * matching the SQL engine.
 */
class EsQueryParser {

    private static final Set<String> COMMAND_KEYS = Set.of(
            "search", "count", "get", "mget", "index", "bulk",
            "update_by_query", "delete_by_query", "create_index", "put_mapping", "delete_index");

    private final EngineMessages messages;

    EsQueryParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result (query type, referenced index, routing hints). */
    SqlParseResult parse(String query) {
        var command = parseCommand(query);
        boolean hasWhere = command.query() != null && !isMatchAll(command.query());
        boolean hasLimit = command.size() != null && command.operation().isRead();
        return new SqlParseResult(
                command.operation().queryType(),
                false,
                List.of(query),
                Set.of(command.index().toLowerCase(Locale.ROOT)),
                hasWhere,
                hasLimit);
    }

    /** Full parse to the executable {@link EsCommand}; reused by the executor. */
    EsCommand parseCommand(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.elasticsearch.blank");
        }
        JsonNode root;
        try {
            root = EsJson.parse(query.strip());
        } catch (EsJson.JsonException ex) {
            throw invalid("error.elasticsearch.invalid_json", ex.snippet());
        }
        if (root == null || !root.isObject() || root.isEmpty()) {
            throw invalid("error.elasticsearch.unrecognized_form");
        }
        try {
            EsJson.assertNoForbiddenConstructs(root);
            var key = commandKey(root);
            var index = requireText(root, key, "index");
            validateIndex(index);
            var command = build(key, index, root);
            validate(command);
            return command;
        } catch (EsParseException ex) {
            throw invalid(ex.messageKey(), ex.args());
        }
    }

    private String commandKey(JsonNode root) {
        String firstField = null;
        for (var entry : root.properties()) {
            if (firstField == null) {
                firstField = entry.getKey();
            }
            if (COMMAND_KEYS.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        throw new EsParseException("error.elasticsearch.unsupported_operation", firstField);
    }

    private EsCommand build(String key, String index, JsonNode root) {
        return switch (key) {
            case "search" -> EsCommand.builder(EsOperation.SEARCH, index)
                    .query(node(root, "query"))
                    .size(integer(root, "size"))
                    .from(integer(root, "from"))
                    .sort(node(root, "sort"))
                    .source(node(root, "_source"))
                    .build();
            case "count" -> EsCommand.builder(EsOperation.COUNT, index)
                    .query(node(root, "query"))
                    .build();
            case "get" -> EsCommand.builder(EsOperation.SEARCH, index)
                    .query(EsJson.idsQuery(List.of(requireText(root, "id", "id"))))
                    .size(1)
                    .build();
            case "mget" -> mget(index, root);
            case "index" -> EsCommand.builder(EsOperation.INDEX, index)
                    .document(requireObject(root, "document"))
                    .docId(text(root, "id"))
                    .build();
            case "bulk" -> EsCommand.builder(EsOperation.BULK, index)
                    .bulkItems(bulkItems(root))
                    .build();
            case "update_by_query" -> EsCommand.builder(EsOperation.UPDATE_BY_QUERY, index)
                    .query(node(root, "query"))
                    .build();
            case "delete_by_query" -> EsCommand.builder(EsOperation.DELETE_BY_QUERY, index)
                    .query(node(root, "query"))
                    .build();
            case "create_index" -> EsCommand.builder(EsOperation.CREATE_INDEX, index)
                    .mappings(node(root, "mappings"))
                    .settings(node(root, "settings"))
                    .build();
            case "put_mapping" -> EsCommand.builder(EsOperation.PUT_MAPPING, index)
                    .properties(requireObject(root, "properties"))
                    .build();
            case "delete_index" -> EsCommand.builder(EsOperation.DELETE_INDEX, index).build();
            default -> throw new EsParseException("error.elasticsearch.unsupported_operation", key);
        };
    }

    private EsCommand mget(String index, JsonNode root) {
        var idsNode = root.get("ids");
        if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
            throw new EsParseException("error.elasticsearch.argument_required", "ids");
        }
        var ids = new ArrayList<String>(idsNode.size());
        for (var element : idsNode) {
            ids.add(element.asString());
        }
        return EsCommand.builder(EsOperation.SEARCH, index)
                .query(EsJson.idsQuery(ids))
                .size(ids.size())
                .build();
    }

    private List<EsBulkItem> bulkItems(JsonNode root) {
        var ops = root.get("operations");
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            throw new EsParseException("error.elasticsearch.argument_required", "operations");
        }
        var items = new ArrayList<EsBulkItem>(ops.size());
        for (var op : ops) {
            if (!op.isObject()) {
                throw new EsParseException("error.elasticsearch.expected_object");
            }
            var document = op.get("document");
            if (document == null || !document.isObject()) {
                // Only index actions are allowed in a bulk; update/delete go via *_by_query.
                throw new EsParseException("error.elasticsearch.bulk_index_only");
            }
            items.add(new EsBulkItem(text(op, "id"), document));
        }
        return items;
    }

    private void validate(EsCommand command) {
        switch (command.operation()) {
            case INDEX -> {
                if (command.document() == null) {
                    throw new EsParseException("error.elasticsearch.argument_required", "document");
                }
            }
            case CREATE_INDEX, PUT_MAPPING, DELETE_INDEX, SEARCH, COUNT, BULK,
                    UPDATE_BY_QUERY, DELETE_BY_QUERY -> {
                // Per-operation required fields already enforced in build(); nothing further.
            }
        }
    }

    private void validateIndex(String index) {
        if (index == null || index.isBlank()) {
            throw new EsParseException("error.elasticsearch.index_required");
        }
        if (index.startsWith("_") || index.startsWith(".")) {
            // System / internal indices and the cluster-API namespace are off-limits.
            throw new EsParseException("error.elasticsearch.forbidden_construct", index);
        }
        for (int i = 0; i < index.length(); i++) {
            char c = index.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == '"' || c == ',') {
                throw new EsParseException("error.elasticsearch.invalid_index", index);
            }
        }
    }

    private static boolean isMatchAll(JsonNode query) {
        return query.isObject() && query.size() == 1 && query.has("match_all");
    }

    private String requireText(JsonNode node, String field, String what) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new EsParseException("error.elasticsearch.argument_required", what);
        }
        return value.asString();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static JsonNode node(JsonNode root, String field) {
        var value = root.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static Integer integer(JsonNode root, String field) {
        var value = root.get(field);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private JsonNode requireObject(JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new EsParseException("error.elasticsearch.argument_required", field);
        }
        return value;
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
