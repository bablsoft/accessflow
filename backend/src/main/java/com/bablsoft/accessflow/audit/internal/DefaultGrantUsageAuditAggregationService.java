package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.GrantUsageAuditAggregationService;
import com.bablsoft.accessflow.audit.api.GrantUsageAuditEvent;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.GrantUsageAuditRepository;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Projects grant-usage events out of {@code audit_log} metadata (#625). Mirrors the UBA reader's
 * approach — an org-scoped, time-bounded, capped row read, with the JSONB parsed in Java through
 * {@link AuditMetadataReader}.
 *
 * <p>Every mapping step is fail-soft: a row whose metadata will not parse, or which carries no
 * resource id, is skipped rather than aborting the batch. In particular an
 * {@code API_REQUEST_EXECUTED} row written before the {@code operation_id} enrichment still yields
 * an event, with empty targets — the call counts as use of the grant, but contributes nothing to
 * the exercised-scope set, so an unenriched history can never make a grant look over-scoped.
 */
@Service
@RequiredArgsConstructor
class DefaultGrantUsageAuditAggregationService implements GrantUsageAuditAggregationService {

    private static final String QUERY_EXECUTED = "QUERY_EXECUTED";
    private static final String QUERY_BREAK_GLASS_EXECUTED = "QUERY_BREAK_GLASS_EXECUTED";
    private static final String API_REQUEST_EXECUTED = "API_REQUEST_EXECUTED";
    private static final String API_REQUEST_BREAK_GLASS_EXECUTED = "API_REQUEST_BREAK_GLASS_EXECUTED";
    private static final List<String> USAGE_ACTIONS = List.of(QUERY_EXECUTED,
            QUERY_BREAK_GLASS_EXECUTED, API_REQUEST_EXECUTED, API_REQUEST_BREAK_GLASS_EXECUTED);

    private final GrantUsageAuditRepository grantUsageAuditRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<GrantUsageAuditEvent> findUsageEvents(UUID organizationId, Instant afterOccurredAt,
                                                      UUID afterAuditLogId, Instant to,
                                                      int maxRows) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be > 0");
        }
        if (afterOccurredAt == null || to == null || !afterOccurredAt.isBefore(to)) {
            return List.of();
        }
        var rows = grantUsageAuditRepository.findUsageEvents(organizationId, USAGE_ACTIONS,
                afterOccurredAt, afterAuditLogId == null ? START : afterAuditLogId, to,
                PageRequest.of(0, maxRows));
        var events = new ArrayList<GrantUsageAuditEvent>(rows.size());
        for (AuditLogEntity row : rows) {
            var metadata = AuditMetadataReader.parse(row, objectMapper);
            if (metadata == null) {
                continue;
            }
            var event = toEvent(row, metadata);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private static GrantUsageAuditEvent toEvent(AuditLogEntity row, JsonNode metadata) {
        return switch (row.getAction()) {
            case QUERY_EXECUTED, QUERY_BREAK_GLASS_EXECUTED -> datasourceEvent(row, metadata);
            case API_REQUEST_EXECUTED, API_REQUEST_BREAK_GLASS_EXECUTED -> connectorEvent(row, metadata);
            default -> null;
        };
    }

    private static GrantUsageAuditEvent datasourceEvent(AuditLogEntity row, JsonNode metadata) {
        var datasourceId = uuidOrNull(AuditMetadataReader.textOrNull(metadata, "datasource_id"));
        if (datasourceId == null) {
            return null;
        }
        return new GrantUsageAuditEvent(row.getId(), row.getOrganizationId(), row.getActorId(),
                GrantResourceKind.DATASOURCE, datasourceId, row.getCreatedAt(),
                AuditMetadataReader.stringArray(metadata, "referenced_tables"));
    }

    private static GrantUsageAuditEvent connectorEvent(AuditLogEntity row, JsonNode metadata) {
        var connectorId = uuidOrNull(AuditMetadataReader.textOrNull(metadata, "connector_id"));
        if (connectorId == null) {
            return null;
        }
        var operationId = AuditMetadataReader.textOrNull(metadata, "operation_id");
        var targets = operationId == null || operationId.isBlank()
                ? List.<String>of()
                : List.of(operationId);
        return new GrantUsageAuditEvent(row.getId(), row.getOrganizationId(), row.getActorId(),
                GrantResourceKind.API_CONNECTOR, connectorId, row.getCreatedAt(), targets);
    }

    private static UUID uuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
