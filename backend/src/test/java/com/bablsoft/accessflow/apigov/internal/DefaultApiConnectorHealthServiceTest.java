package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.events.ApiConnectorUnhealthyEvent;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorProber;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorHealthCheckEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCheckRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorHealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiConnectorHealthCheckRepository healthCheckRepository;
    @Mock private ApiConnectorProber prober;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private DefaultApiConnectorHealthService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var properties = new ApigovRequestProperties(1L, 1L, 1L, 1, 1, 3, null);
        service = new DefaultApiConnectorHealthService(connectorRepository, healthCheckRepository, prober,
                auditLogService, eventPublisher, properties, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    private ApiConnectorEntity connector(ApiConnectorHealthStatus status, int failures) {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl("https://api.example.test");
        entity.setTimeoutMs(2000);
        entity.setHealthCheckIntervalMinutes(5);
        entity.setLastProbeStatus(status);
        entity.setConsecutiveProbeFailures(failures);
        when(connectorRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(connectorRepository.findByIdForUpdate(entity.getId())).thenReturn(Optional.of(entity));
        return entity;
    }

    private void probeReturns(boolean success, String message) {
        when(prober.probe(ApiProtocol.REST, "https://api.example.test", 2000))
                .thenReturn(new ApiConnectionTestResult(success, message));
    }

    private ApiConnectorHealthCheckEntity savedCheck() {
        var captor = ArgumentCaptor.forClass(ApiConnectorHealthCheckEntity.class);
        verify(healthCheckRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void firstSuccessMovesUnknownToHealthyWithoutAudit() {
        var c = connector(ApiConnectorHealthStatus.UNKNOWN, 0);
        probeReturns(true, "HTTP 200");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.HEALTHY, NOW, 0);
        var check = savedCheck();
        assertThat(check.getConnectorId()).isEqualTo(c.getId());
        assertThat(check.getCheckedAt()).isEqualTo(NOW);
        assertThat(check.isSuccess()).isTrue();
        assertThat(check.getMessage()).isEqualTo("HTTP 200");
        assertThat(check.getDurationMs()).isZero();
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void successWhileHealthyStaysSilent() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 1);
        probeReturns(true, "HTTP 200");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.HEALTHY, NOW, 0);
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void failureBelowThresholdKeepsPreviousStatus() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 1);
        probeReturns(false, "Connection refused");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.HEALTHY, NOW, 2);
        assertThat(savedCheck().isSuccess()).isFalse();
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void thresholdCrossingFlipsToUnhealthyAuditsAndPublishesOnce() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 2);
        probeReturns(false, "Connection refused");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.UNHEALTHY, NOW, 3);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.API_CONNECTOR_UNHEALTHY);
        assertThat(audit.getValue().resourceType()).isEqualTo(AuditResourceType.API_CONNECTOR);
        assertThat(audit.getValue().resourceId()).isEqualTo(c.getId());
        assertThat(audit.getValue().organizationId()).isEqualTo(orgId);
        assertThat(audit.getValue().actorId()).isNull();
        assertThat(audit.getValue().metadata())
                .containsEntry("trigger", "health")
                .containsEntry("consecutive_failures", 3)
                .containsEntry("message", "Connection refused");
        verify(eventPublisher).publishEvent(new ApiConnectorUnhealthyEvent(c.getId(), orgId, 3,
                "Connection refused"));
    }

    @Test
    void unknownConnectorCanFlipStraightToUnhealthy() {
        var c = connector(ApiConnectorHealthStatus.UNKNOWN, 2);
        probeReturns(false, null);

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.UNHEALTHY, NOW, 3);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().metadata()).doesNotContainKey("message");
        verify(eventPublisher).publishEvent(any(ApiConnectorUnhealthyEvent.class));
    }

    @Test
    void failurePastThresholdPublishesNothing() {
        var c = connector(ApiConnectorHealthStatus.UNHEALTHY, 3);
        probeReturns(false, "Connection refused");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.UNHEALTHY, NOW, 4);
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void successAfterUnhealthyRecoversAndAudits() {
        var c = connector(ApiConnectorHealthStatus.UNHEALTHY, 7);
        probeReturns(true, "HTTP 200");

        service.probe(c.getId());

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.HEALTHY, NOW, 0);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.API_CONNECTOR_RECOVERED);
        assertThat(audit.getValue().metadata()).containsEntry("trigger", "health");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void throwingAuditDoesNotFailTheProbe() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 2);
        probeReturns(false, "Connection refused");
        when(auditLogService.record(any())).thenThrow(new IllegalStateException("audit down"));

        assertThatCode(() -> service.probe(c.getId())).doesNotThrowAnyException();

        verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.UNHEALTHY, NOW, 3);
        verify(eventPublisher).publishEvent(any(ApiConnectorUnhealthyEvent.class));
    }

    @Test
    void longMessageIsTruncated() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 0);
        probeReturns(false, "x".repeat(5000));

        service.probe(c.getId());

        assertThat(savedCheck().getMessage()).hasSize(DefaultApiConnectorHealthService.MAX_MESSAGE_CHARS);
        assertThat(DefaultApiConnectorHealthService.truncate(null)).isNull();
    }

    @Test
    void missingConnectorIsSkipped() {
        var id = UUID.randomUUID();
        when(connectorRepository.findById(id)).thenReturn(Optional.empty());

        service.probe(id);

        verifyNoInteractions(prober, healthCheckRepository, auditLogService, eventPublisher);
        verify(connectorRepository, never()).updateProbeOutcome(any(), any(), any(), anyInt());
    }

    @Test
    void healthReturnsCurrentStateAndNewestChecks() {
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setHealthCheckIntervalMinutes(5);
        c.setLastProbeStatus(ApiConnectorHealthStatus.UNHEALTHY);
        c.setLastProbeAt(NOW);
        c.setConsecutiveProbeFailures(4);
        when(connectorRepository.findByIdAndOrganizationId(c.getId(), orgId)).thenReturn(Optional.of(c));
        var checks = IntStream.range(0, 20).mapToObj(i -> {
            var check = new ApiConnectorHealthCheckEntity();
            check.setId(UUID.randomUUID());
            check.setConnectorId(c.getId());
            check.setCheckedAt(NOW.minusSeconds(60L * i));
            check.setSuccess(i % 2 == 0);
            check.setMessage("m" + i);
            check.setDurationMs(i);
            return check;
        }).toList();
        when(healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(c.getId())).thenReturn(checks);

        var view = service.health(c.getId(), orgId);

        assertThat(view.status()).isEqualTo(ApiConnectorHealthStatus.UNHEALTHY);
        assertThat(view.lastProbeAt()).isEqualTo(NOW);
        assertThat(view.consecutiveFailures()).isEqualTo(4);
        assertThat(view.healthCheckIntervalMinutes()).isEqualTo(5);
        assertThat(view.recentChecks()).hasSize(20);
        assertThat(view.recentChecks().getFirst().checkedAt()).isEqualTo(NOW);
        assertThat(view.recentChecks().getFirst().message()).isEqualTo("m0");
        assertThat(view.recentChecks().get(1).success()).isFalse();
        assertThat(view.recentChecks().get(1).durationMs()).isEqualTo(1);
    }

    @Test
    void healthOfConnectorInAnotherOrganizationIsNotFound() {
        var id = UUID.randomUUID();
        when(connectorRepository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.health(id, orgId)).isInstanceOf(ApiConnectorNotFoundException.class);
        verifyNoInteractions(healthCheckRepository);
    }

    @Test
    void healthViewToleratesNullCheckList() {
        assertThat(new com.bablsoft.accessflow.apigov.api.ApiConnectorHealthView(
                ApiConnectorHealthStatus.UNKNOWN, null, 0, null, null).recentChecks()).isEqualTo(List.of());
    }

    @Test
    void outcomeIsWrittenBeforeTheFlipAudit() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 2);
        probeReturns(false, "Connection refused");

        service.probe(c.getId());

        var order = inOrder(connectorRepository, auditLogService, eventPublisher);
        order.verify(connectorRepository).updateProbeOutcome(c.getId(), ApiConnectorHealthStatus.UNHEALTHY, NOW, 3);
        order.verify(auditLogService).record(any());
        order.verify(eventPublisher).publishEvent(any(ApiConnectorUnhealthyEvent.class));
    }

    @Test
    void probeOverlappingMonitoringBeingTurnedOffIsDropped() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 2);
        probeReturns(false, "Connection refused");
        var cleared = new ApiConnectorEntity();
        cleared.setId(c.getId());
        cleared.setOrganizationId(orgId);
        cleared.setHealthCheckIntervalMinutes(null);
        when(connectorRepository.findByIdForUpdate(c.getId())).thenReturn(Optional.of(cleared));

        service.probe(c.getId());

        verifyNoInteractions(healthCheckRepository, auditLogService, eventPublisher);
        verify(connectorRepository, never()).updateProbeOutcome(any(), any(), any(), anyInt());
    }

    @Test
    void probeOfConnectorDeactivatedOrDeletedMeanwhileIsDropped() {
        var c = connector(ApiConnectorHealthStatus.HEALTHY, 0);
        probeReturns(true, "HTTP 200");
        c.setActive(false);

        service.probe(c.getId());

        var other = connector(ApiConnectorHealthStatus.HEALTHY, 0);
        when(prober.probe(ApiProtocol.REST, "https://api.example.test", 2000))
                .thenReturn(new ApiConnectionTestResult(true, "HTTP 200"));
        when(connectorRepository.findByIdForUpdate(other.getId())).thenReturn(Optional.empty());

        service.probe(other.getId());

        verifyNoInteractions(healthCheckRepository);
        verify(connectorRepository, never()).updateProbeOutcome(any(), any(), any(), anyInt());
    }
}
