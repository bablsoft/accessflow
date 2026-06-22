package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.BehaviorAuditAggregationService;
import com.bablsoft.accessflow.audit.api.BehaviorAuditSample;
import com.bablsoft.accessflow.audit.api.BehaviorSubjectRef;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.BehaviorAuditRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads behavioural feature inputs from {@code audit_log} metadata only. The per-subject sample
 * projection parses the entity's JSONB {@code metadata} string in Java (small per-window volume),
 * filtering to the requested datasource and pulling {@code query_type} / {@code referenced_tables} /
 * {@code rows_returned} when present (older rows without the AF-383 enrichment yield fewer fields).
 */
@Service
@RequiredArgsConstructor
class DefaultBehaviorAuditAggregationService implements BehaviorAuditAggregationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultBehaviorAuditAggregationService.class);
    private static final List<String> QUERY_ACTIONS = List.of("QUERY_EXECUTED", "QUERY_FAILED");

    private final BehaviorAuditRepository behaviorAuditRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<BehaviorSubjectRef> findActiveSubjects(Instant from, Instant to) {
        return behaviorAuditRepository.findActiveSubjects(from, to).stream()
                .map(p -> new BehaviorSubjectRef(p.getOrganizationId(), p.getUserId(),
                        p.getDatasourceId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BehaviorAuditSample> samplesFor(UUID organizationId, UUID userId, UUID datasourceId,
                                                Instant from, Instant to) {
        var rows = behaviorAuditRepository
                .findByOrganizationIdAndActorIdAndActionInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                        organizationId, userId, QUERY_ACTIONS, from, to);
        var samples = new ArrayList<BehaviorAuditSample>(rows.size());
        var datasource = datasourceId.toString();
        for (AuditLogEntity row : rows) {
            JsonNode metadata = parseMetadata(row);
            if (metadata == null) {
                continue;
            }
            if (!datasource.equals(textOrNull(metadata, "datasource_id"))) {
                continue;
            }
            samples.add(new BehaviorAuditSample(
                    row.getCreatedAt(),
                    "QUERY_EXECUTED".equals(row.getAction()),
                    textOrNull(metadata, "query_type"),
                    referencedTables(metadata),
                    rowsReturned(metadata)));
        }
        return samples;
    }

    private JsonNode parseMetadata(AuditLogEntity row) {
        var raw = row.getMetadata();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (RuntimeException ex) {
            log.warn("Skipping audit row {} with unparseable metadata", row.getId());
            return null;
        }
    }

    private static String textOrNull(JsonNode metadata, String field) {
        var node = metadata.path(field);
        return node.isString() ? node.asString() : null;
    }

    private static List<String> referencedTables(JsonNode metadata) {
        var node = metadata.path("referenced_tables");
        if (!node.isArray()) {
            return List.of();
        }
        var tables = new ArrayList<String>(node.size());
        node.forEach(t -> {
            if (t.isString()) {
                tables.add(t.asString());
            }
        });
        return tables;
    }

    private static Long rowsReturned(JsonNode metadata) {
        var node = metadata.path("rows_returned");
        return node.isNumber() ? node.asLong() : null;
    }
}
