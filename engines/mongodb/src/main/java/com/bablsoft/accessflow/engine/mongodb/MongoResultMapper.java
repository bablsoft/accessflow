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
 * applied per value via the shared {@link ColumnMasker}, identical to the SQL engine.
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
            var mask = matcher.maskFor(field);
            columns.add(new ResultColumn(field, Types.OTHER, bsonTypeName(firstNonNull(docs, field)),
                    mask != null));
            if (mask != null && mask.policyId() != null) {
                appliedPolicyIds.add(mask.policyId());
            }
        }

        var rows = new ArrayList<List<Object>>(docs.size());
        for (var doc : docs) {
            var row = new ArrayList<>(fields.size());
            for (var field : fields) {
                var raw = doc.get(field);
                var mask = matcher.maskFor(field);
                row.add(mask == null ? convert(raw) : maskValue(raw, mask));
            }
            rows.add(row);
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration,
                Set.copyOf(appliedPolicyIds));
    }

    private static Object maskValue(Object raw, MaskMatcher.AppliedMask mask) {
        if (raw == null) {
            return null;
        }
        if (mask.strategy() == MaskingStrategy.FULL) {
            return ColumnMasker.FULL_MASK;
        }
        return ColumnMasker.apply(mask.strategy(), String.valueOf(convert(raw)), mask.params());
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
        var out = new java.util.LinkedHashMap<String, Object>();
        for (var entry : doc.entrySet()) {
            out.put(entry.getKey(), convert(entry.getValue()));
        }
        return out;
    }

    private static Map<String, Object> convertMap(Map<?, ?> map) {
        var out = new java.util.LinkedHashMap<String, Object>();
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
     * Resolves the masking that applies to a top-level field, mirroring the SQL
     * {@code ColumnMaskResolver} precedence ({@code collection.field} → bare {@code field}; the
     * database-qualified level is unused here). Explicit mask directives win over a bare
     * restricted-columns entry, which defaults to {@link MaskingStrategy#FULL}.
     */
    private static final class MaskMatcher {

        record AppliedMask(MaskingStrategy strategy, Map<String, String> params, UUID policyId) {
        }

        private final List<DirectiveRef> directives;
        private final List<RefKeys> restricted;

        MaskMatcher(List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks) {
            this.directives = new ArrayList<>();
            this.restricted = new ArrayList<>();
            if (columnMasks != null) {
                for (var directive : columnMasks) {
                    if (directive != null && directive.columnRef() != null
                            && !directive.columnRef().isBlank()) {
                        directives.add(new DirectiveRef(RefKeys.parse(directive.columnRef()), directive));
                    }
                }
            }
            if (restrictedColumns != null) {
                for (var entry : restrictedColumns) {
                    if (entry != null && !entry.isBlank()) {
                        restricted.add(RefKeys.parse(entry));
                    }
                }
            }
        }

        AppliedMask maskFor(String field) {
            var column = field.toLowerCase(Locale.ROOT);
            ColumnMaskDirective best = null;
            int bestLevel = 0;
            for (var ref : directives) {
                int level = ref.keys().matchLevel(column);
                if (level > bestLevel) {
                    bestLevel = level;
                    best = ref.directive();
                }
            }
            if (best != null) {
                return new AppliedMask(best.strategy(), best.params(), best.policyId());
            }
            for (var ref : restricted) {
                if (ref.matchLevel(column) > 0) {
                    return new AppliedMask(MaskingStrategy.FULL, Map.of(), null);
                }
            }
            return null;
        }

        private record DirectiveRef(RefKeys keys, ColumnMaskDirective directive) {
        }

        private record RefKeys(String table, String bare) {

            static RefKeys parse(String entry) {
                var lower = entry.trim().toLowerCase(Locale.ROOT);
                var parts = lower.split("\\.");
                return parts.length == 1
                        ? new RefKeys(null, parts[0])
                        : new RefKeys(parts[parts.length - 2] + "." + parts[parts.length - 1],
                                parts[parts.length - 1]);
            }

            /** 2 = collection.field, 1 = bare field, 0 = no match (no schema level here). */
            int matchLevel(String column) {
                if (table != null && table.endsWith("." + column)) {
                    return 2;
                }
                return bare.equals(column) ? 1 : 0;
            }
        }
    }
}
