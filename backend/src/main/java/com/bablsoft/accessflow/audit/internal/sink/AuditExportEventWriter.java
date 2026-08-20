package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts {@code audit_log} rows into the canonical export-event JSON shared by every sink
 * type (#628). Field names are snake_case on the wire; {@code metadata} is embedded as a JSON
 * object; {@code created_at} keeps the stored microsecond precision via ISO-8601.
 */
@Component
@RequiredArgsConstructor
public class AuditExportEventWriter {

    private final ObjectMapper objectMapper;

    public AuditExportEvent toEvent(AuditLogEntity row) {
        return new AuditExportEvent(
                row.getId(),
                row.getOrganizationId(),
                row.getActorId(),
                row.getAction(),
                row.getResourceType(),
                row.getResourceId(),
                row.getMetadata(),
                row.getIpAddress(),
                row.getUserAgent(),
                row.getCreatedAt(),
                hexOrNull(row.getPreviousHash()),
                hexOrNull(row.getCurrentHash()));
    }

    /** One event as a single-line JSON object. */
    public String toJson(AuditExportEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("id", event.id());
        fields.put("organization_id", event.organizationId());
        fields.put("actor_id", event.actorId());
        fields.put("action", event.action());
        fields.put("resource_type", event.resourceType());
        fields.put("resource_id", event.resourceId());
        fields.put("metadata", metadataNode(event.metadataJson()));
        fields.put("ip_address", event.ipAddress());
        fields.put("user_agent", event.userAgent());
        fields.put("created_at", event.createdAt() == null ? null : event.createdAt().toString());
        fields.put("previous_hash", event.previousHash());
        fields.put("current_hash", event.currentHash());
        return objectMapper.writeValueAsString(fields);
    }

    /** A batch as a JSON array (the HTTPS_BATCH body). */
    public String toJsonArray(List<AuditExportEvent> batch) {
        return batch.stream().map(this::toJson).collect(Collectors.joining(",", "[", "]"));
    }

    /** A batch as JSONL — one event per line, trailing newline (the S3 segment body). */
    public String toJsonLines(List<AuditExportEvent> batch) {
        return batch.stream().map(this::toJson).collect(Collectors.joining("\n", "", "\n"));
    }

    private JsonNode metadataNode(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(metadataJson);
        } catch (RuntimeException ex) {
            // Stored metadata is written by AuditLogService and is always valid JSON; a corrupt
            // row must not wedge the whole stream, so export it as a string instead.
            var node = objectMapper.createObjectNode();
            node.put("raw", metadataJson);
            return node;
        }
    }

    private static String hexOrNull(byte[] bytes) {
        return bytes == null ? null : HexFormat.of().formatHex(bytes);
    }
}
