package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftFindingEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaDriftFindingEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var scan = new SchemaDriftScanEntity();
        var environmentId = UUID.randomUUID();
        var firstDetectedAt = Instant.parse("2026-09-01T10:00:00Z");
        var lastSeenAt = Instant.parse("2026-09-02T10:00:00Z");
        var resolvedAt = Instant.parse("2026-09-03T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setScan(scan);
        entity.setEnvironmentId(environmentId);
        entity.setObjectPath("public.orders.total");
        entity.setFindingKind(SchemaDriftFindingKind.NULLABILITY_MISMATCH);
        entity.setExpectedValue("NOT NULL");
        entity.setActualValue("NULL");
        entity.setStatus(SchemaDriftFindingStatus.RESOLVED);
        entity.setFirstDetectedAt(firstDetectedAt);
        entity.setLastSeenAt(lastSeenAt);
        entity.setResolvedAt(resolvedAt);
        entity.setVersion(4L);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getScan()).isSameAs(scan);
        assertThat(entity.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(entity.getObjectPath()).isEqualTo("public.orders.total");
        assertThat(entity.getFindingKind()).isEqualTo(SchemaDriftFindingKind.NULLABILITY_MISMATCH);
        assertThat(entity.getExpectedValue()).isEqualTo("NOT NULL");
        assertThat(entity.getActualValue()).isEqualTo("NULL");
        assertThat(entity.getStatus()).isEqualTo(SchemaDriftFindingStatus.RESOLVED);
        assertThat(entity.getFirstDetectedAt()).isEqualTo(firstDetectedAt);
        assertThat(entity.getLastSeenAt()).isEqualTo(lastSeenAt);
        assertThat(entity.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(entity.getVersion()).isEqualTo(4L);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaDriftFindingEntity();

        assertThat(entity.getStatus()).isEqualTo(SchemaDriftFindingStatus.OPEN);
        assertThat(entity.getScan()).isNull();
        assertThat(entity.getExpectedValue()).isNull();
        assertThat(entity.getActualValue()).isNull();
        assertThat(entity.getFirstDetectedAt()).isNotNull();
        assertThat(entity.getLastSeenAt()).isNotNull();
        assertThat(entity.getResolvedAt()).isNull();
    }

    @Test
    void aNewFindingStartsAtVersionZero() {
        // V181: the scan's stale write must lose to an acknowledgement made while it ran.
        assertThat(new SchemaDriftFindingEntity().getVersion()).isZero();
    }
}
