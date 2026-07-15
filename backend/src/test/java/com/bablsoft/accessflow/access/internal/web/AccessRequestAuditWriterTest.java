package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccessRequestAuditWriterTest {

    @Mock AuditLogService auditLogService;
    @InjectMocks AccessRequestAuditWriter writer;

    private final UUID requestId = UUID.randomUUID();
    private final JwtClaims caller = JwtClaims.forSystemRole(UUID.randomUUID(), "u@x.io", UserRoleType.ADMIN, UUID.randomUUID());
    private final RequestAuditContext ctx = new RequestAuditContext("1.2.3.4", "agent");

    @Test
    void recordWritesAuditEntryWithRequestContext() {
        writer.record(AuditAction.ACCESS_REQUEST_SUBMITTED, requestId, caller,
                Map.of("k", "v"), ctx);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.ACCESS_REQUEST_SUBMITTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.ACCESS_GRANT_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(requestId);
        assertThat(entry.actorId()).isEqualTo(caller.userId());
        assertThat(entry.ipAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void recordSwallowsAuditFailure() {
        doThrow(new RuntimeException("boom")).when(auditLogService).record(org.mockito.ArgumentMatchers.any());
        assertThatCode(() -> writer.record(AuditAction.ACCESS_REQUEST_CANCELLED, requestId, caller,
                Map.of(), ctx)).doesNotThrowAnyException();
    }
}
