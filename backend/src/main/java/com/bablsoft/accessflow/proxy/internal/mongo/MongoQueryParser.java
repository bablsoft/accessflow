package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import org.bson.Document;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates a submitted MongoDB query in either supported form — the mongo-shell method
 * chain ({@code db.users.find({ age: { $gt: 21 } }).limit(10)}) or the native JSON command document
 * ({@code { "find": "users", "filter": { ... } }}) — into an engine-neutral {@link MongoCommand}.
 * The form is auto-detected: a query that begins with {@code db.} is shell, one that begins with
 * <code>{</code> is a command document. Anything else, a multi-statement input, an unsupported
 * operation, or a forbidden operator ({@code $where}/{@code $function}/{@code $out}/{@code $merge})
 * is rejected with {@link InvalidSqlException} (HTTP 422), matching the SQL engine.
 */
@Component
public class MongoQueryParser {

    private static final Set<String> READ_MODIFIERS = Set.of("limit", "skip", "sort");

    private final MessageSource messageSource;

    public MongoQueryParser(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Engine-neutral parse result for the workflow layer (query type, collection, routing hints). */
    public SqlParseResult parse(String query) {
        var command = parseCommand(query);
        boolean hasFilter = (command.filter() != null && !command.filter().isEmpty())
                || command.pipeline().stream().anyMatch(stage -> stage.containsKey("$match"));
        boolean hasLimit = command.limit() != null;
        return new SqlParseResult(
                command.operation().queryType(),
                false,
                List.of(query),
                Set.of(command.collection().toLowerCase(Locale.ROOT)),
                hasFilter,
                hasLimit && command.operation().isRead());
    }

    /** Full parse to the executable {@link MongoCommand}; reused by the executor. */
    MongoCommand parseCommand(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.mongo.blank");
        }
        var trimmed = stripTrailingSemicolon(query.strip());
        try {
            MongoCommand command = trimmed.startsWith("{")
                    ? parseCommandDocument(trimmed)
                    : trimmed.startsWith("db.")
                    ? parseShell(trimmed)
                    : raise("error.mongo.unrecognized_form");
            validate(command);
            return command;
        } catch (MongoParseException ex) {
            throw invalid(ex.messageKey(), ex.args());
        }
    }

    // ---- shell form -------------------------------------------------------------------------

    private MongoCommand parseShell(String query) {
        var segments = tokenize(query);
        if (segments.isEmpty()) {
            throw new MongoParseException("error.mongo.unrecognized_form");
        }
        var first = segments.get(0);
        // Database-level helper: db.createCollection("name", options?)
        if (first.name().equals("createCollection") && first.args() != null) {
            var args = MongoJson.splitArgs(first.args());
            requireExtraSegments(segments, 1);
            var name = requireString(args, 0, "collection name");
            Document options = args.size() > 1 ? MongoJson.parseDocument(args.get(1)) : null;
            return MongoCommand.builder(name, MongoOperation.CREATE_COLLECTION)
                    .options(options).build();
        }
        String collection;
        int opIndex;
        if (first.name().equals("getCollection") && first.args() != null) {
            var args = MongoJson.splitArgs(first.args());
            collection = requireString(args, 0, "collection name");
            opIndex = 1;
        } else if (first.args() == null) {
            collection = first.name();
            opIndex = 1;
        } else {
            throw new MongoParseException("error.mongo.unrecognized_form");
        }
        if (segments.size() <= opIndex) {
            throw new MongoParseException("error.mongo.operation_required");
        }
        var opSegment = segments.get(opIndex);
        var operation = MongoOperation.fromShell(opSegment.name());
        if (operation == null || opSegment.args() == null) {
            throw new MongoParseException("error.mongo.unsupported_operation", opSegment.name());
        }
        var args = MongoJson.splitArgs(opSegment.args());
        var builder = buildShellOperation(collection, operation, args);
        applyModifiers(builder, operation, segments, opIndex + 1);
        return builder.build();
    }

    private MongoCommand.Builder buildShellOperation(String collection, MongoOperation operation,
                                                     List<String> args) {
        var b = MongoCommand.builder(collection, operation);
        switch (operation) {
            case FIND -> b.filter(docArg(args, 0)).projection(docArg(args, 1));
            case AGGREGATE -> b.pipeline(arrayArg(args, 0, "pipeline"));
            case COUNT_DOCUMENTS -> b.filter(docArg(args, 0));
            case DISTINCT -> b.distinctKey(requireString(args, 0, "distinct key"))
                    .filter(docArg(args, 1));
            case INSERT_ONE -> b.documents(List.of(requireDoc(args, 0, "document")));
            case INSERT_MANY -> b.documents(arrayArg(args, 0, "documents"));
            case UPDATE_ONE, UPDATE_MANY, REPLACE_ONE, FIND_ONE_AND_UPDATE -> b
                    .filter(requireDoc(args, 0, "filter"))
                    .update(requireDoc(args, 1, "update"));
            case DELETE_ONE, DELETE_MANY -> b.filter(requireDoc(args, 0, "filter"));
            case CREATE_COLLECTION -> { /* handled at the db level */ }
            case CREATE_INDEX -> b.indexKeys(requireDoc(args, 0, "index keys"))
                    .options(docArg(args, 1));
            case DROP_COLLECTION -> { /* no args */ }
            case DROP_INDEX -> b.options(new Document("name",
                    requireString(args, 0, "index name")));
        }
        return b;
    }

    private void applyModifiers(MongoCommand.Builder builder, MongoOperation operation,
                                List<Segment> segments, int from) {
        for (int i = from; i < segments.size(); i++) {
            var segment = segments.get(i);
            if (segment.args() == null || !READ_MODIFIERS.contains(segment.name())
                    || operation != MongoOperation.FIND) {
                throw new MongoParseException("error.mongo.unsupported_modifier", segment.name());
            }
            var args = MongoJson.splitArgs(segment.args());
            switch (segment.name()) {
                case "limit" -> builder.limit(requireInt(args, "limit"));
                case "skip" -> builder.skip(requireInt(args, "skip"));
                case "sort" -> builder.sort(requireDoc(args, 0, "sort"));
                default -> throw new MongoParseException("error.mongo.unsupported_modifier",
                        segment.name());
            }
        }
    }

    /** Tokenize {@code db.<seg>[.<seg>]*} into property/method segments. */
    private List<Segment> tokenize(String query) {
        var segments = new ArrayList<Segment>();
        int i = 2; // skip leading "db"
        int len = query.length();
        while (i < len) {
            if (query.charAt(i) != '.') {
                throw new MongoParseException("error.mongo.unrecognized_form");
            }
            i++;
            int nameStart = i;
            while (i < len && (Character.isLetterOrDigit(query.charAt(i))
                    || query.charAt(i) == '_' || query.charAt(i) == '$')) {
                i++;
            }
            if (i == nameStart) {
                throw new MongoParseException("error.mongo.unrecognized_form");
            }
            var name = query.substring(nameStart, i);
            if (i < len && query.charAt(i) == '(') {
                int close = matchingParen(query, i);
                segments.add(new Segment(name, query.substring(i + 1, close)));
                i = close + 1;
            } else {
                segments.add(new Segment(name, null));
            }
        }
        return segments;
    }

    private int matchingParen(String query, int open) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < query.length(); i++) {
            char c = query.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> quote = c;
                case '(' -> depth++;
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                default -> { /* scan */ }
            }
        }
        throw new MongoParseException("error.mongo.unbalanced");
    }

    // ---- JSON command form ------------------------------------------------------------------

    private MongoCommand parseCommandDocument(String query) {
        var doc = MongoJson.parseDocument(query);
        if (doc == null || doc.isEmpty()) {
            throw new MongoParseException("error.mongo.unrecognized_form");
        }
        String commandKey = null;
        MongoOperation operation = null;
        for (var key : doc.keySet()) {
            var candidate = MongoOperation.fromCommandKey(key);
            if (candidate != null) {
                commandKey = key;
                operation = candidate;
                break;
            }
        }
        if (operation == null) {
            throw new MongoParseException("error.mongo.unsupported_operation",
                    doc.keySet().iterator().next());
        }
        var collection = asString(doc.get(commandKey), "collection name");
        var b = MongoCommand.builder(collection, operation);
        switch (operation) {
            case FIND -> b.filter(docField(doc, "filter")).projection(docField(doc, "projection"))
                    .sort(docField(doc, "sort")).limit(intField(doc, "limit"))
                    .skip(intField(doc, "skip"));
            case AGGREGATE -> b.pipeline(docArrayField(doc, "pipeline", "pipeline"));
            case COUNT_DOCUMENTS -> b.filter(docField(doc, "query"));
            case DISTINCT -> b.distinctKey(asString(doc.get("key"), "distinct key"))
                    .filter(docField(doc, "query"));
            case INSERT_MANY -> b.documents(docArrayField(doc, "documents", "documents"));
            case UPDATE_MANY -> applyCommandUpdate(b, doc);
            case DELETE_MANY -> applyCommandDelete(b, doc);
            case FIND_ONE_AND_UPDATE -> b.filter(docField(doc, "query"))
                    .update(requireField(doc, "update", "update"));
            case CREATE_COLLECTION -> { /* collection name only */ }
            case CREATE_INDEX -> applyCommandCreateIndex(b, doc);
            case DROP_COLLECTION -> { /* collection name only */ }
            case DROP_INDEX -> b.options(new Document("name",
                    asString(doc.get("index"), "index name")));
            default -> throw new MongoParseException("error.mongo.unsupported_operation", commandKey);
        }
        return b.build();
    }

    private void applyCommandUpdate(MongoCommand.Builder b, Document doc) {
        var updates = docArrayField(doc, "updates", "update");
        var first = updates.get(0);
        b.filter(asDocument(first.get("q"))).update(asDocument(first.get("u")));
        if (!Boolean.TRUE.equals(first.get("multi"))) {
            b.narrowOperation(MongoOperation.UPDATE_ONE);
        }
    }

    private void applyCommandDelete(MongoCommand.Builder b, Document doc) {
        var deletes = docArrayField(doc, "deletes", "filter");
        var first = deletes.get(0);
        b.filter(asDocument(first.get("q")));
        if (first.get("limit") instanceof Number n && n.intValue() == 1) {
            b.narrowOperation(MongoOperation.DELETE_ONE);
        }
    }

    private void applyCommandCreateIndex(MongoCommand.Builder b, Document doc) {
        var indexes = docArrayField(doc, "indexes", "index keys");
        var first = indexes.get(0);
        b.indexKeys(asDocument(first.get("key")));
        if (first.get("name") != null) {
            b.options(new Document("name", first.get("name")));
        }
    }

    // ---- validation -------------------------------------------------------------------------

    private void validate(MongoCommand command) {
        if (command.collection() == null || command.collection().isBlank()) {
            throw new MongoParseException("error.mongo.collection_required");
        }
        MongoJson.assertNoForbiddenOperators(command.filter());
        MongoJson.assertNoForbiddenOperators(command.update());
        command.pipeline().forEach(MongoJson::assertNoForbiddenOperators);
        command.documents().forEach(MongoJson::assertNoForbiddenOperators);
        if (command.operation() == MongoOperation.AGGREGATE && command.pipeline().isEmpty()) {
            throw new MongoParseException(ARG_REQUIRED, "pipeline");
        }
        if ((command.operation() == MongoOperation.INSERT_ONE
                || command.operation() == MongoOperation.INSERT_MANY)
                && command.documents().isEmpty()) {
            throw new MongoParseException(ARG_REQUIRED, "documents");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private record Segment(String name, String args) {
    }

    private static String stripTrailingSemicolon(String q) {
        var trimmed = q;
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static void requireExtraSegments(List<Segment> segments, int expected) {
        if (segments.size() > expected) {
            throw new MongoParseException("error.mongo.unsupported_modifier",
                    segments.get(expected).name());
        }
    }

    private static final String ARG_REQUIRED = "error.mongo.argument_required";

    private static Document docArg(List<String> args, int index) {
        if (index >= args.size()) {
            return null;
        }
        return MongoJson.parseDocument(args.get(index));
    }

    private static Document requireDoc(List<String> args, int index, String what) {
        var doc = docArg(args, index);
        if (doc == null) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        return doc;
    }

    private static List<Document> arrayArg(List<String> args, int index, String what) {
        if (index >= args.size()) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        return MongoJson.parseDocumentArray(args.get(index));
    }

    private static String requireString(List<String> args, int index, String what) {
        if (index >= args.size()) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        return asString(MongoJson.parseValue(args.get(index)), what);
    }

    private static int requireInt(List<String> args, String what) {
        if (args.isEmpty()) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        var value = MongoJson.parseValue(args.get(0));
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new MongoParseException(ARG_REQUIRED, what);
    }

    private static String asString(Object value, String what) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new MongoParseException(ARG_REQUIRED, what);
    }

    private static Document asDocument(Object value) {
        if (value == null) {
            return new Document();
        }
        if (value instanceof Document d) {
            return d;
        }
        throw new MongoParseException("error.mongo.expected_object");
    }

    private static Document docField(Document doc, String field) {
        var value = doc.get(field);
        return value == null ? null : asDocument(value);
    }

    private static Document requireField(Document doc, String field, String what) {
        var value = doc.get(field);
        if (value == null) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        return asDocument(value);
    }

    private static Integer intField(Document doc, String field) {
        var value = doc.get(field);
        return value instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> docArrayField(Document doc, String field, String what) {
        var value = doc.get(field);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new MongoParseException(ARG_REQUIRED, what);
        }
        for (var element : list) {
            if (!(element instanceof Document)) {
                throw new MongoParseException("error.mongo.expected_object");
            }
        }
        return (List<Document>) list;
    }

    private static <T> T raise(String key) {
        throw new MongoParseException(key);
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
    }
}
