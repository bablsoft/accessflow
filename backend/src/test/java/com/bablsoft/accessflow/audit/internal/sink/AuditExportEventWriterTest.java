package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExportEventWriterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AuditExportEventWriter writer = new AuditExportEventWriter(mapper);

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T10:15:30.123456Z");

    private AuditLogEntity entity() {
        var row = new AuditLogEntity();
        row.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        row.setOrganizationId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        row.setActorId(UUID.fromString("99999999-8888-7777-6666-555555555555"));
        row.setAction("QUERY_SUBMITTED");
        row.setResourceType("query_request");
        row.setResourceId(UUID.fromString("12121212-3434-5656-7878-909090909090"));
        row.setMetadata("{\"sql\":\"SELECT 1\"}");
        row.setIpAddress("203.0.113.5");
        row.setUserAgent("ua/1");
        row.setCreatedAt(CREATED_AT);
        row.setPreviousHash(new byte[]{0x00, (byte) 0xAB, (byte) 0xFF});
        row.setCurrentHash(new byte[]{0x01, 0x2C});
        return row;
    }

    @Test
    void toEventMapsAllFieldsAndHexesHashes() {
        var event = writer.toEvent(entity());

        assertThat(event.id()).isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        assertThat(event.organizationId())
                .isEqualTo(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(event.actorId())
                .isEqualTo(UUID.fromString("99999999-8888-7777-6666-555555555555"));
        assertThat(event.action()).isEqualTo("QUERY_SUBMITTED");
        assertThat(event.resourceType()).isEqualTo("query_request");
        assertThat(event.resourceId())
                .isEqualTo(UUID.fromString("12121212-3434-5656-7878-909090909090"));
        assertThat(event.metadataJson()).isEqualTo("{\"sql\":\"SELECT 1\"}");
        assertThat(event.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(event.userAgent()).isEqualTo("ua/1");
        assertThat(event.createdAt()).isEqualTo(CREATED_AT);
        assertThat(event.previousHash()).isEqualTo("00abff");
        assertThat(event.currentHash()).isEqualTo("012c");
    }

    @Test
    void toEventKeepsNullHashesNull() {
        var row = entity();
        row.setPreviousHash(null);
        row.setCurrentHash(null);

        var event = writer.toEvent(row);

        assertThat(event.previousHash()).isNull();
        assertThat(event.currentHash()).isNull();
    }

    @Test
    void toJsonUsesSnakeCaseNamesAndEmbedsMetadataAsObject() {
        var json = writer.toJson(writer.toEvent(entity()));

        var node = mapper.readTree(json);
        assertThat(node.get("id").asString())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(node.get("organization_id").asString())
                .isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(node.get("actor_id").asString())
                .isEqualTo("99999999-8888-7777-6666-555555555555");
        assertThat(node.get("action").asString()).isEqualTo("QUERY_SUBMITTED");
        assertThat(node.get("resource_type").asString()).isEqualTo("query_request");
        assertThat(node.get("resource_id").asString())
                .isEqualTo("12121212-3434-5656-7878-909090909090");
        // Metadata is an embedded object, never a double-encoded string.
        assertThat(node.get("metadata").isObject()).isTrue();
        assertThat(node.get("metadata").get("sql").asString()).isEqualTo("SELECT 1");
        assertThat(node.get("ip_address").asString()).isEqualTo("203.0.113.5");
        assertThat(node.get("user_agent").asString()).isEqualTo("ua/1");
        assertThat(node.get("created_at").asString()).isEqualTo("2026-08-19T10:15:30.123456Z");
        assertThat(node.get("previous_hash").asString()).isEqualTo("00abff");
        assertThat(node.get("current_hash").asString()).isEqualTo("012c");
    }

    @Test
    void toJsonFallsBackToRawWrapperOnCorruptMetadata() {
        var row = entity();
        row.setMetadata("{corrupt");

        var node = mapper.readTree(writer.toJson(writer.toEvent(row)));

        assertThat(node.get("metadata").isObject()).isTrue();
        assertThat(node.get("metadata").get("raw").asString()).isEqualTo("{corrupt");
    }

    @Test
    void toJsonWritesEmptyObjectForBlankMetadata() {
        var row = entity();
        row.setMetadata(" ");

        var node = mapper.readTree(writer.toJson(writer.toEvent(row)));

        assertThat(node.get("metadata").isObject()).isTrue();
        assertThat(node.get("metadata").size()).isZero();
    }

    @Test
    void toJsonArrayWrapsEventsInAJsonArray() {
        var event = writer.toEvent(entity());

        var array = writer.toJsonArray(List.of(event, event));

        var node = mapper.readTree(array);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.get(0).get("action").asString()).isEqualTo("QUERY_SUBMITTED");
    }

    @Test
    void toJsonLinesEmitsOneLinePerEventWithTrailingNewline() {
        var event = writer.toEvent(entity());

        var jsonl = writer.toJsonLines(List.of(event, event));

        assertThat(jsonl).endsWith("\n");
        var lines = jsonl.split("\n");
        assertThat(lines).hasSize(2);
        for (var line : lines) {
            assertThat(mapper.readTree(line).get("id").asString())
                    .isEqualTo("11111111-2222-3333-4444-555555555555");
        }
    }
}
