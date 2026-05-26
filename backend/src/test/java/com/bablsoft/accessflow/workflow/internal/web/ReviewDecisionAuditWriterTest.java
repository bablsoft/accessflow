package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReviewDecisionAuditWriterTest {

    @Mock AuditLogService auditLogService;
    @InjectMocks ReviewDecisionAuditWriter writer;

    private final UUID queryId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final JwtClaims caller = new JwtClaims(userId, "r@example.com",
            UserRoleType.REVIEWER, orgId);
    private final RequestAuditContext auditContext = new RequestAuditContext("10.0.0.1", "ua/1");

    @Test
    void recordsSuccessfulDecisionWithCommentMetadata() {
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                QueryStatus.APPROVED, false);

        writer.record(AuditAction.QUERY_APPROVED, queryId, caller, outcome, "looks good",
                auditContext);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(userId);
        assertThat(entry.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(entry.userAgent()).isEqualTo("ua/1");
        assertThat(entry.metadata())
                .containsEntry("comment", "looks good")
                .containsEntry("resulting_status", "APPROVED");
    }

    @Test
    void omitsCommentMetadataWhenBlank() {
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                QueryStatus.APPROVED, false);

        writer.record(AuditAction.QUERY_APPROVED, queryId, caller, outcome, "   ", auditContext);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().metadata()).doesNotContainKey("comment");
    }

    @Test
    void skipsAuditWhenIdempotentReplay() {
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                QueryStatus.APPROVED, true);

        writer.record(AuditAction.QUERY_APPROVED, queryId, caller, outcome, "x", auditContext);

        verify(auditLogService, never()).record(any(AuditEntry.class));
    }

    @Test
    void swallowsAuditServiceFailure() {
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                QueryStatus.APPROVED, false);
        doThrow(new RuntimeException("boom")).when(auditLogService)
                .record(any(AuditEntry.class));

        // Should NOT throw — audit failures must not break the response path.
        writer.record(AuditAction.QUERY_APPROVED, queryId, caller, outcome, "x", auditContext);
    }
}
