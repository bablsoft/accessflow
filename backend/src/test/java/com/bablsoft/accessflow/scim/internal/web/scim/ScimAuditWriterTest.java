package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScimAuditWriterTest {

    @Mock AuditLogService auditLogService;

    private final ScimPrincipal principal =
            new ScimPrincipal(UUID.randomUUID(), UUID.randomUUID(), "okta-prod");
    private final RequestAuditContext auditContext =
            new RequestAuditContext("10.0.0.1", "Okta-Provisioning");

    @Test
    void recordsNullActorRowWithTokenMetadata() {
        var writer = new ScimAuditWriter(auditLogService);
        var resourceId = UUID.randomUUID();

        writer.record(AuditAction.SCIM_USER_PROVISIONED, AuditResourceType.USER, resourceId,
                principal, Map.of("email", "jane@example.com"), auditContext);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.actorId()).isNull();
        assertThat(entry.organizationId()).isEqualTo(principal.organizationId());
        assertThat(entry.metadata())
                .containsEntry("email", "jane@example.com")
                .containsEntry("scim_token_id", principal.tokenId().toString())
                .containsEntry("scim_token_name", "okta-prod");
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void auditFailuresAreSwallowed() {
        var writer = new ScimAuditWriter(auditLogService);
        doThrow(new IllegalStateException("chain broken")).when(auditLogService).record(any());

        assertThatCode(() -> writer.record(AuditAction.SCIM_USER_UPDATED, AuditResourceType.USER,
                UUID.randomUUID(), principal, Map.of(), auditContext))
                .doesNotThrowAnyException();
    }
}
