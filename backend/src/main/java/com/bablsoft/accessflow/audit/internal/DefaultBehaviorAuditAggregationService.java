package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.BehaviorAuditAggregationService;
import com.bablsoft.accessflow.audit.api.BehaviorAuditSample;
import com.bablsoft.accessflow.audit.api.BehaviorSubjectRef;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.BehaviorAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
            var metadata = AuditMetadataReader.parse(row, objectMapper);
            if (metadata == null) {
                continue;
            }
            if (!datasource.equals(AuditMetadataReader.textOrNull(metadata, "datasource_id"))) {
                continue;
            }
            samples.add(new BehaviorAuditSample(
                    row.getCreatedAt(),
                    "QUERY_EXECUTED".equals(row.getAction()),
                    AuditMetadataReader.textOrNull(metadata, "query_type"),
                    AuditMetadataReader.stringArray(metadata, "referenced_tables"),
                    AuditMetadataReader.longOrNull(metadata, "rows_returned")));
        }
        return samples;
    }
}
