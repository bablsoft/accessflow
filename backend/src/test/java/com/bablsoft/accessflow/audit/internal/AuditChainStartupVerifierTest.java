package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditChainVerificationSummary;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditLogVerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditChainStartupVerifierTest {

    @Mock AuditLogService auditLogService;
    @InjectMocks AuditChainStartupVerifier verifier;

    @Test
    void verifiesEveryOrganizationOnStartup() {
        when(auditLogService.verifyAllOrganizations()).thenReturn(List.of(
                new AuditChainVerificationSummary(UUID.randomUUID(),
                        AuditLogVerificationResult.ok(12))));

        verifier.verifyOnStartup();

        verify(auditLogService).verifyAllOrganizations();
    }

    @Test
    void logsBrokenChainWithoutThrowing() {
        when(auditLogService.verifyAllOrganizations()).thenReturn(List.of(
                new AuditChainVerificationSummary(UUID.randomUUID(),
                        AuditLogVerificationResult.ok(3)),
                new AuditChainVerificationSummary(UUID.randomUUID(),
                        AuditLogVerificationResult.fail(1, UUID.randomUUID(),
                                Instant.parse("2026-01-01T00:00:00Z"), "current_hash_mismatch"))));

        assertThatCode(() -> verifier.verifyOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void handlesEmptyAuditLog() {
        when(auditLogService.verifyAllOrganizations()).thenReturn(List.of());

        assertThatCode(() -> verifier.verifyOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void neverPropagatesVerificationFailures() {
        when(auditLogService.verifyAllOrganizations())
                .thenThrow(new IllegalStateException("db unavailable"));

        assertThatCode(() -> verifier.verifyOnStartup()).doesNotThrowAnyException();
    }
}
