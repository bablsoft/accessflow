package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
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
class DiscoveryAuditWriterTest {

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private JwtClaims caller;
    @Mock
    private RequestAuditContext auditContext;

    private final UUID findingId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void recordsEntryWithCallerAndContext() {
        when(caller.organizationId()).thenReturn(orgId);
        when(caller.userId()).thenReturn(userId);
        when(auditContext.ipAddress()).thenReturn("10.0.0.1");
        when(auditContext.userAgent()).thenReturn("test-agent");

        new DiscoveryAuditWriter(auditLogService).record(
                AuditAction.DISCOVERY_FINDING_CONFIRMED, findingId, caller,
                Map.of("columnName", "email"), auditContext);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DISCOVERY_FINDING_CONFIRMED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DISCOVERY_FINDING);
        assertThat(entry.resourceId()).isEqualTo(findingId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(userId);
        assertThat(entry.metadata()).containsEntry("columnName", "email");
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(entry.userAgent()).isEqualTo("test-agent");
    }

    @Test
    void auditFailureIsSwallowed() {
        when(caller.organizationId()).thenReturn(orgId);
        when(caller.userId()).thenReturn(userId);
        when(auditLogService.record(any())).thenThrow(new IllegalStateException("audit down"));

        assertThatCode(() -> new DiscoveryAuditWriter(auditLogService).record(
                AuditAction.DISCOVERY_FINDING_DISMISSED, findingId, caller, Map.of(),
                auditContext)).doesNotThrowAnyException();
    }
}
