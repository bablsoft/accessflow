package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Pure diff of two parsed operation catalogs, keyed by {@code operationId}. A {@code null} request or
 * response shape means "unknown" and never produces {@link ApiSchemaDriftKind#SHAPE_CHANGED}. The
 * caller applies any operation filter to both sides first; this class never looks at one.
 */
@Component
public class OperationCatalogDiff {

    private static final Comparator<OperationDiffFinding> ORDER =
            Comparator.comparing(OperationDiffFinding::operationId)
                    .thenComparing(f -> f.kind().ordinal());

    private static final Comparator<ApiOperation> BY_ID =
            Comparator.comparing(ApiOperation::operationId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ObjectMapper objectMapper;

    public OperationCatalogDiff(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Findings that turn {@code current} into {@code candidate}, sorted by operation id then kind. */
    public List<OperationDiffFinding> diff(List<ApiOperation> current, List<ApiOperation> candidate) {
        var before = index(current);
        var after = index(candidate);
        var ids = new TreeSet<String>(before.keySet());
        ids.addAll(after.keySet());
        var findings = new ArrayList<OperationDiffFinding>();
        for (var id : ids) {
            var b = before.get(id);
            var a = after.get(id);
            if (b == null) {
                findings.add(new OperationDiffFinding(ApiSchemaDriftKind.OPERATION_ADDED, id, null, a));
            } else if (a == null) {
                findings.add(new OperationDiffFinding(ApiSchemaDriftKind.OPERATION_REMOVED, id, b, null));
            } else {
                compare(id, b, a, findings);
            }
        }
        findings.sort(ORDER);
        return List.copyOf(findings);
    }

    /**
     * SHA-256 (lower-case hex) over the canonical JSON of a catalog: operations sorted by id, fields
     * in {@link ApiOperation} declaration order, nulls written explicitly. Built as an explicit tree so
     * the value does not depend on the mapper's inclusion or property-ordering configuration.
     */
    public String contentHash(List<ApiOperation> operations) {
        var sorted = (operations == null ? List.<ApiOperation>of() : operations).stream()
                .filter(Objects::nonNull)
                .sorted(BY_ID)
                .toList();
        var array = objectMapper.createArrayNode();
        sorted.forEach(op -> array.add(canonical(op)));
        var json = objectMapper.writeValueAsString(array);
        return HexFormat.of().formatHex(sha256().digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void compare(String id, ApiOperation b, ApiOperation a, List<OperationDiffFinding> findings) {
        if (b.write() != a.write()) {
            findings.add(new OperationDiffFinding(ApiSchemaDriftKind.CLASSIFICATION_FLIPPED, id, b, a));
        }
        if (!Objects.equals(b.verb(), a.verb())
                || !Objects.equals(b.path(), a.path())
                || shapeDiffers(b.requestSchema(), a.requestSchema())
                || shapeDiffers(b.responseSchema(), a.responseSchema())) {
            findings.add(new OperationDiffFinding(ApiSchemaDriftKind.SHAPE_CHANGED, id, b, a));
        }
        if (!Boolean.TRUE.equals(b.deprecated()) && Boolean.TRUE.equals(a.deprecated())) {
            findings.add(new OperationDiffFinding(ApiSchemaDriftKind.DEPRECATED, id, b, a));
        }
    }

    private boolean shapeDiffers(String before, String after) {
        if (before == null || after == null) {
            return false;
        }
        var b = parse(before);
        var a = parse(after);
        if (b != null && a != null) {
            return !b.equals(a);
        }
        return !before.trim().equals(after.trim());
    }

    private JsonNode parse(String shape) {
        if (shape.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(shape);
        } catch (JacksonException ex) {
            // Not JSON (e.g. a proto message name) — the caller falls back to a trimmed string compare.
            return null;
        }
    }

    private ObjectNode canonical(ApiOperation op) {
        var node = objectMapper.createObjectNode();
        node.put("operationId", op.operationId());
        node.put("verb", op.verb());
        node.put("path", op.path());
        node.put("summary", op.summary());
        node.put("write", op.write());
        node.put("requestSchema", op.requestSchema());
        node.put("responseSchema", op.responseSchema());
        if (op.tags() == null) {
            node.putNull("tags");
        } else {
            var tags = node.putArray("tags");
            op.tags().forEach(tags::add);
        }
        node.put("deprecated", op.deprecated());
        return node;
    }

    private static Map<String, ApiOperation> index(List<ApiOperation> operations) {
        var byId = new LinkedHashMap<String, ApiOperation>();
        if (operations != null) {
            // Every parser guarantees an id and de-duplicates; first occurrence wins as a guard.
            operations.stream()
                    .filter(op -> op != null && op.operationId() != null)
                    .forEach(op -> byId.putIfAbsent(op.operationId(), op));
        }
        return byId;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
