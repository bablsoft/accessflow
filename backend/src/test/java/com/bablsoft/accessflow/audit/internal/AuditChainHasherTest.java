package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditChainHasherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] keyA = repeat((byte) 0x01, 32);
    private final byte[] keyB = repeat((byte) 0x02, 32);
    private final AuditChainHasher hasherA = new AuditChainHasher(keyA, mapper);
    private final AuditChainHasher hasherB = new AuditChainHasher(keyB, mapper);

    @Test
    void rejectsShortKey() {
        assertThatThrownBy(() -> new AuditChainHasher(new byte[31], mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditChainHasher(null, mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashIsDeterministicForSameInput() {
        var row = baseRow();
        var first = hasherA.hash(row, null);
        var second = hasherA.hash(row, null);
        assertThat(first).hasSize(32).isEqualTo(second);
    }

    @Test
    void differentKeyProducesDifferentHash() {
        var row = baseRow();
        assertThat(hasherA.hash(row, null)).isNotEqualTo(hasherB.hash(row, null));
    }

    @Test
    void previousHashChangesCurrentHash() {
        var row = baseRow();
        var withoutPrev = hasherA.hash(row, null);
        var withPrev = hasherA.hash(row, repeat((byte) 0x42, 32));
        assertThat(withoutPrev).isNotEqualTo(withPrev);
    }

    @Test
    void nullActorAndNullableFieldsAreHandled() {
        var row = baseRow();
        row.setActorId(null);
        row.setResourceId(null);
        row.setIpAddress(null);
        row.setUserAgent(null);
        // Should not throw and should produce a 32-byte digest.
        assertThat(hasherA.hash(row, null)).hasSize(32);
    }

    @Test
    void metadataKeyOrderDoesNotAffectHash() {
        var first = baseRow();
        first.setMetadata("{\"a\":1,\"b\":2}");
        var second = baseRow();
        second.setMetadata("{\"b\":2,\"a\":1}");
        assertThat(hasherA.hash(first, null)).isEqualTo(hasherA.hash(second, null));
    }

    @Test
    void nestedMetadataIsNormalised() {
        var first = baseRow();
        first.setMetadata("{\"o\":{\"x\":1,\"y\":2},\"a\":[1,2]}");
        var second = baseRow();
        second.setMetadata("{\"a\":[1,2],\"o\":{\"y\":2,\"x\":1}}");
        assertThat(hasherA.hash(first, null)).isEqualTo(hasherA.hash(second, null));
    }

    @Test
    void changingMetadataValueChangesHash() {
        var first = baseRow();
        first.setMetadata("{\"a\":1}");
        var second = baseRow();
        second.setMetadata("{\"a\":2}");
        assertThat(hasherA.hash(first, null)).isNotEqualTo(hasherA.hash(second, null));
    }

    @Test
    void blankMetadataIsTreatedAsEmptyObject() {
        var first = baseRow();
        first.setMetadata("");
        var second = baseRow();
        second.setMetadata("{}");
        assertThat(hasherA.hash(first, null)).isEqualTo(hasherA.hash(second, null));
    }

    @Test
    void changingAnyFieldChangesHash() {
        var base = baseRow();
        var ref = hasherA.hash(base, null);

        var changedAction = baseRow();
        changedAction.setAction("QUERY_REJECTED");
        assertThat(hasherA.hash(changedAction, null)).isNotEqualTo(ref);

        var changedTime = baseRow();
        changedTime.setCreatedAt(Instant.parse("2026-01-01T00:00:01Z"));
        assertThat(hasherA.hash(changedTime, null)).isNotEqualTo(ref);

        var changedActor = baseRow();
        changedActor.setActorId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(hasherA.hash(changedActor, null)).isNotEqualTo(ref);
    }

    private static byte[] repeat(byte value, int length) {
        var bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static AuditLogEntity baseRow() {
        var row = new AuditLogEntity();
        row.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        row.setOrganizationId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        row.setActorId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        row.setAction("QUERY_SUBMITTED");
        row.setResourceType("query_request");
        row.setResourceId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        row.setMetadata("{\"foo\":\"bar\"}");
        row.setIpAddress("10.0.0.1");
        row.setUserAgent("Mozilla/5.0");
        row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return row;
    }
}
