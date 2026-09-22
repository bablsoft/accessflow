package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaChangeAuditWriterTest {

    private AuditLogService auditLogService;
    private SchemaChangeAuditWriter writer;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        writer = new SchemaChangeAuditWriter(auditLogService);
    }

    @Test
    void writesAPromotionScopedEntry() {
        var promotionId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var actorId = UUID.randomUUID();

        writer.record(AuditAction.SCHEMA_CHANGE_PROMOTION_SUBMITTED, promotionId, orgId, actorId,
                Map.of("statement_count", 3), "10.0.0.7", "curl/8");

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue()).extracting("action", "resourceType", "resourceId", "organizationId", "actorId",
                        "ipAddress", "userAgent")
                .containsExactly(AuditAction.SCHEMA_CHANGE_PROMOTION_SUBMITTED,
                        AuditResourceType.SCHEMA_CHANGE_PROMOTION, promotionId, orgId, actorId, "10.0.0.7", "curl/8");
        assertThat(entry.getValue().metadata()).containsEntry("statement_count", 3);
    }

    @Test
    void writesASystemEntryWithoutAnActor() {
        writer.record(AuditAction.SCHEMA_CHANGE_PROMOTION_APPLIED, UUID.randomUUID(), UUID.randomUUID(), null,
                Map.of("trigger", "request_group"), null, null);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().actorId()).isNull();
        assertThat(entry.getValue().metadata()).containsEntry("trigger", "request_group");
    }

    /** A failed audit write must never break the promotion it describes. */
    @Test
    void swallowsAFailedWrite() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        assertThatCode(() -> writer.record(AuditAction.SCHEMA_CHANGE_PROMOTION_FAILED, UUID.randomUUID(),
                UUID.randomUUID(), null, Map.of(), null, null)).doesNotThrowAnyException();
    }
}
