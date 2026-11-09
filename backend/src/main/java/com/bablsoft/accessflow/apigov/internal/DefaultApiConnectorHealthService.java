package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthCheckView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Scheduled connector health (#894). Reuses {@link ApiConnectorProber} exactly as the admin test
 * endpoint does — and nothing else: no OAuth2 token fetch, which has its own failure counter and
 * alert. The flip to {@code UNHEALTHY} is gated on the previous status, so it audits and publishes
 * once per outage however long the streak runs; recovery is audited, never notified.
 */
@Service
@Slf4j
public class DefaultApiConnectorHealthService implements ApiConnectorHealthService {

    static final int MAX_MESSAGE_CHARS = 1024;

    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorHealthCheckRepository healthCheckRepository;
    private final ApiConnectorProber prober;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ApigovRequestProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DefaultApiConnectorHealthService(ApiConnectorRepository connectorRepository,
                                            ApiConnectorHealthCheckRepository healthCheckRepository,
                                            ApiConnectorProber prober, AuditLogService auditLogService,
                                            ApplicationEventPublisher eventPublisher,
                                            ApigovRequestProperties properties, Clock clock,
                                            PlatformTransactionManager transactionManager) {
        this.connectorRepository = connectorRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.prober = prober;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The outbound probe runs outside any transaction, so no pooled connection is held for up to
     * {@code timeoutMs}. The outcome is then written in one short transaction against a row-locked,
     * re-read connector: a probe that overlapped an admin turning monitoring off is dropped, and two
     * overlapping probes cannot both see the pre-flip status. The audit row (its own transaction) is
     * written after the outcome, so a failed outcome write never leaves an orphaned flip audit.
     */
    @Override
    public void probe(UUID connectorId) {
        var connector = connectorRepository.findById(connectorId).orElse(null);
        if (connector == null) {
            log.debug("Skipping health probe of API connector {}: it no longer exists", connectorId);
            return;
        }
        var started = clock.instant();
        var result = prober.probe(connector.getProtocol(), connector.getBaseUrl(), connector.getTimeoutMs());
        var checkedAt = clock.instant();
        var message = truncate(result.message());
        var duration = Duration.between(started, checkedAt);
        transactionTemplate.executeWithoutResult(status ->
                recordOutcome(connectorId, result.success(), message, checkedAt, duration));
    }

    private void recordOutcome(UUID connectorId, boolean success, String message, Instant checkedAt,
                               Duration duration) {
        var connector = connectorRepository.findByIdForUpdate(connectorId).orElse(null);
        if (connector == null || !connector.isActive() || connector.getHealthCheckIntervalMinutes() == null) {
            log.debug("Dropping health probe of API connector {}: no longer monitored", connectorId);
            return;
        }
        recordCheck(connectorId, checkedAt, success, message, duration);

        var previous = connector.getLastProbeStatus();
        var next = previous;
        int failures;
        AuditAction transition = null;
        if (success) {
            failures = 0;
            next = ApiConnectorHealthStatus.HEALTHY;
            if (previous == ApiConnectorHealthStatus.UNHEALTHY) {
                transition = AuditAction.API_CONNECTOR_RECOVERED;
            }
        } else {
            failures = connector.getConsecutiveProbeFailures() + 1;
            if (failures >= properties.healthFailureThreshold() && previous != ApiConnectorHealthStatus.UNHEALTHY) {
                next = ApiConnectorHealthStatus.UNHEALTHY;
                transition = AuditAction.API_CONNECTOR_UNHEALTHY;
            }
        }
        connectorRepository.updateProbeOutcome(connectorId, next, checkedAt, failures);
        if (transition != null) {
            audit(connector, transition, failures, message);
        }
        if (transition == AuditAction.API_CONNECTOR_UNHEALTHY) {
            eventPublisher.publishEvent(new ApiConnectorUnhealthyEvent(connectorId,
                    connector.getOrganizationId(), failures, message));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectorHealthView health(UUID connectorId, UUID organizationId) {
        var connector = connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
        var checks = healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(connectorId).stream()
                .map(c -> new ApiConnectorHealthCheckView(c.getCheckedAt(), c.isSuccess(), c.getMessage(),
                        c.getDurationMs()))
                .toList();
        return new ApiConnectorHealthView(connector.getLastProbeStatus(), connector.getLastProbeAt(),
                connector.getConsecutiveProbeFailures(), connector.getHealthCheckIntervalMinutes(), checks);
    }

    private void recordCheck(UUID connectorId, Instant checkedAt, boolean success, String message,
                             Duration duration) {
        var check = new ApiConnectorHealthCheckEntity();
        check.setId(UUID.randomUUID());
        check.setConnectorId(connectorId);
        check.setCheckedAt(checkedAt);
        check.setSuccess(success);
        check.setMessage(message);
        check.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, duration.toMillis())));
        healthCheckRepository.save(check);
    }

    private void audit(ApiConnectorEntity connector, AuditAction action, int failures, String message) {
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("trigger", "health");
            metadata.put("consecutive_failures", failures);
            if (message != null) {
                metadata.put("message", message);
            }
            auditLogService.record(new AuditEntry(action, AuditResourceType.API_CONNECTOR, connector.getId(),
                    connector.getOrganizationId(), null, metadata, null, null));
        } catch (RuntimeException ex) {
            // Audit must never fail the probe.
            log.warn("Failed to audit {} for API connector {}", action, connector.getId(), ex);
        }
    }

    static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS);
    }
}
