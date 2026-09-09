package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes a page of MongoDB result {@link Document}s into the engine-neutral
 * {@link SelectExecutionResult}. Columns are the ordered union of top-level field names across the
 * page (so heterogeneous documents still render as a table); each row aligns its values to that
 * column order, with absent fields as {@code null}. Nested objects/arrays are preserved as
 * {@link Map}/{@link List} so the persisted JSON stays valid and the UI can both flatten to a table
 * and reconstruct the documents for the JSON view. BSON scalar types (ObjectId, Decimal128, Date,
 * Binary, UUID) are normalized to JSON-friendly values. Restricted columns and masking policies are
 * applied per value via the shared {@link ColumnMasker}, <em>recursively by dot-path</em> (AF-658)
 * so a mask on {@code profile.ssn} redacts the nested leaf while the rest of {@code profile} stays
 * visible; a whole-field {@code FULL} mask collapses the subtree, and a list is a fan-out point
 * rather than a path segment, so {@code contacts.email} covers every element.
 */
class MongoResultMapper {

    private static final String BASE64_PREFIX = "base64:";

    SelectExecutionResult materialize(List<Document> fetched, int maxRows, Duration duration,
                                      List<String> restrictedColumns,
                                      List<ColumnMaskDirective> columnMasks) {
        boolean truncated = fetched.size() > maxRows;
        var docs = truncated ? fetched.subList(0, maxRows) : fetched;

        var fieldOrder = new LinkedHashSet<String>();
        for (var doc : docs) {
            fieldOrder.addAll(doc.keySet());
        }
        var fields = new ArrayList<>(fieldOrder);
        var matcher = new MaskMatcher(restrictedColumns, columnMasks);
        var appliedPolicyIds = new LinkedHashSet<UUID>();

        var columns = new ArrayList<ResultColumn>(fields.size());
        for (var field : fields) {
            var mask = matcher.maskForPath(field);
            columns.add(new ResultColumn(field, Types.OTHER, bsonTypeName(firstNonNull(docs, field)),
                    mask != null || matcher.hasRuleAtOrUnder(field)));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(docs.size());
        for (var doc : docs) {
            var row = new ArrayList<>(fields.size());
            for (var field : fields) {
                // Normalize BSON first so the dot-path walk sees plain Map/List/scalar values.
                var converted = convert(doc.get(field));
                // With no rules at all there is nothing to find, so skip the walk entirely rather
                // than deep-copying every document of a large result.
                row.add(matcher.isEmpty() ? converted
                        : maskValue(converted, field, matcher, appliedPolicyIds));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    private static Object maskValue(Object value, String path, MaskMatcher matcher,
                                    LinkedHashSet<UUID> appliedPolicyIds) {
        if (value == null) {
            return null;
        }
        var mask = matcher.maskForPath(path);
        if (mask != null) {
            if (mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
            if (mask.strategy() == MaskingStrategy.FULL) {
                // Redact the whole subtree rather than leaking its keys and shape.
                return ColumnMasker.FULL_MASK;
            }
            return ColumnMasker.apply(mask.strategy(), String.valueOf(value), mask.params());
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                out.put(key, maskValue(entry.getValue(), path + "." + key, matcher,
                        appliedPolicyIds));
            }
            return out;
        }
        // A list is a fan-out point, not a path segment: every element shares the parent path.
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            for (var element : list) {
                out.add(maskValue(element, path, matcher, appliedPolicyIds));
            }
            return out;
        }
        return value;
    }

    /** Normalize a BSON value into a JSON-serializable Java value. */
    static Object convert(Object value) {
        return switch (value) {
            case null -> null;
            case ObjectId oid -> oid.toHexString();
            case Decimal128 dec -> dec.bigDecimalValue();
            case Date date -> OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC).toString();
            case Instant instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
            case Binary binary -> BASE64_PREFIX + Base64.getEncoder().encodeToString(binary.getData());
            case byte[] bytes -> BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes);
            case UUID uuid -> uuid.toString();
            case Document doc -> convertDocument(doc);
            case Map<?, ?> map -> convertMap(map);
            case List<?> list -> convertList(list);
            default -> value;
        };
    }

    private static Map<String, Object> convertDocument(Document doc) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : doc.entrySet()) {
            out.put(entry.getKey(), convert(entry.getValue()));
        }
        return out;
    }

    private static Map<String, Object> convertMap(Map<?, ?> map) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), convert(entry.getValue()));
        }
        return out;
    }

    private static List<Object> convertList(List<?> list) {
        var out = new ArrayList<>(list.size());
        for (var element : list) {
            out.add(convert(element));
        }
        return out;
    }

    private static Object firstNonNull(List<Document> docs, String field) {
        for (var doc : docs) {
            var value = doc.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
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
            case Instant ignored -> "date";
            case Binary ignored -> "binary";
            case byte[] ignored -> "binary";
            case Document ignored -> "object";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            default -> "string";
        };
    }

    /**
     * Resolves the masking that applies to a (possibly nested) field path, extending the SQL
     * {@code ColumnMaskResolver} precedence to dot-paths: the most qualified ref wins, so a
     * {@code collection.<path>} form beats an exact-path ref, which beats a bare last-segment
     * match; an unmatched bare restricted-columns entry defaults to {@link MaskingStrategy#FULL}.
     * {@link #hasRuleAtOrUnder(String)} lights up a top-level column's restricted flag when only a
     * nested field under it is masked, so an AF-447 tag on a nested dot-path — which the tag
     * derivation always writes collection-qualified — addresses the same leaf here (AF-658).
     *
     * <p>The <em>walk</em> matches the Elasticsearch mapper, but the <em>precedence</em> is
     * deliberately inverted: Elasticsearch ranks an exact path above a qualified one, while these
     * engines keep their pre-existing SQL-style most-qualified-wins order. Given both a
     * {@code profile.ssn} and a {@code users.profile.ssn} policy, MongoDB/Couchbase apply the
     * qualified one and Elasticsearch the exact one.
     */
    private static final class MaskMatcher {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private final List<DirectiveRef> directives = new ArrayList<>();
        private final List<String> restricted = new ArrayList<>();

        MaskMatcher(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        directives.add(new DirectiveRef(
                                directive.columnRef().trim().toLowerCase(Locale.ROOT), directive));
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        restricted.add(entry.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        boolean isEmpty() {
            return directives.isEmpty() && restricted.isEmpty();
        }

        AppliedMask maskForPath(String path) {
            var column = path.toLowerCase(Locale.ROOT);
            ColumnMaskDirective best = null;
            int bestLevel = 0;
            for (var ref : directives) {
                int level = matchLevel(ref.ref(), column);
                if (level > bestLevel) {
                    bestLevel = level;
                    best = ref.directive();
                }
            }
            if (best != null) {
                return new AppliedMask(best.strategy(), best.params(), best.policyId());
            }
            for (var ref : restricted) {
                if (matchLevel(ref, column) > 0) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
            return null;
        }

        boolean hasRuleAtOrUnder(String top) {
            var t = top.toLowerCase(Locale.ROOT);
            for (var ref : directives) {
                if (atOrUnder(ref.ref(), t)) {
                    return true;
                }
            }
            for (var ref : restricted) {
                if (atOrUnder(ref, t)) {
                    return true;
                }
            }
            return false;
        }

        /** 3 = qualified (….path), 2 = exact path, 1 = bare last-segment, 0 = no match. */
        private static int matchLevel(String ref, String path) {
            if (ref.endsWith("." + path)) {
                return 3;
            }
            if (ref.equals(path)) {
                return 2;
            }
            int dot = path.lastIndexOf('.');
            var last = dot >= 0 ? path.substring(dot + 1) : path;
            return ref.equals(last) ? 1 : 0;
        }

        private static boolean atOrUnder(String ref, String top) {
            return ref.equals(top)
                    || ref.startsWith(top + ".")
                    || ref.endsWith("." + top)
                    || ref.contains("." + top + ".");
        }

        private record DirectiveRef(String ref, ColumnMaskDirective directive) {
        }
    }
}
