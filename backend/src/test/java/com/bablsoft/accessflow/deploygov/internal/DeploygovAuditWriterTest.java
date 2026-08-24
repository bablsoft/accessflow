package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploygovAuditWriterTest {

    @Mock
    private AuditLogService auditLogService;

    private final UUID requestId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void recordsEntryWithAllFieldsAndNoUserAgent() {
        new DeploygovAuditWriter(auditLogService).record(
                AuditAction.DEPLOYMENT_BREAK_GLASS_EXECUTED, AuditResourceType.DEPLOYMENT_REQUEST,
                requestId, orgId, userId, Map.of("environment", "production"), "10.0.0.1");

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DEPLOYMENT_BREAK_GLASS_EXECUTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DEPLOYMENT_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(requestId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(userId);
        assertThat(entry.metadata()).containsEntry("environment", "production");
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(entry.userAgent()).isNull();
    }

    @Test
    void auditFailureIsSwallowed() {
        when(auditLogService.record(any())).thenThrow(new IllegalStateException("audit down"));

        assertThatCode(() -> new DeploygovAuditWriter(auditLogService).record(
                AuditAction.DEPLOYMENT_BREAK_GLASS_EXECUTED, AuditResourceType.DEPLOYMENT_REQUEST,
                requestId, orgId, userId, Map.of(), null)).doesNotThrowAnyException();
    }
}
