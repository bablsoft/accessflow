package com.bablsoft.accessflow.audit.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEntryTest {

    private final UUID organizationId = UUID.randomUUID();

    @Test
    void rejectsNullAction() {
        assertThatThrownBy(() -> new AuditEntry(
                null,
                AuditResourceType.USER,
                UUID.randomUUID(),
                organizationId,
                null,
                Map.of(),
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void rejectsNullResourceType() {
        assertThatThrownBy(() -> new AuditEntry(
                AuditAction.USER_LOGIN,
                null,
                UUID.randomUUID(),
                organizationId,
                null,
                Map.of(),
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(() -> new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                UUID.randomUUID(),
                null,
                null,
                Map.of(),
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void nullMetadataNormalizesToEmptyMap() {
        var entry = new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                UUID.randomUUID(),
                organizationId,
                null,
                null,
                null,
                null);
        assertThat(entry.metadata()).isEmpty();
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var mutable = new HashMap<String, Object>();
        mutable.put("k", "v");
        var entry = new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                UUID.randomUUID(),
                organizationId,
                null,
                mutable,
                null,
                null);
        mutable.put("k2", "v2");
        assertThat(entry.metadata()).hasSize(1).containsEntry("k", "v");
        assertThatThrownBy(() -> entry.metadata().put("foo", "bar"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesAllFieldsOnHappyPath() {
        var resourceId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var entry = new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of("a", 1),
                "10.0.0.1",
                "ua");

        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_SUBMITTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(resourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(entry.userAgent()).isEqualTo("ua");
    }
}
