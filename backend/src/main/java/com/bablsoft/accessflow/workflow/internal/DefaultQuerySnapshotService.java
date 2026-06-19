package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultQuerySnapshotService implements QuerySnapshotService {

    private final QuerySnapshotRepository repository;
    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryParser queryParser;
    private final DatasourceAdminService datasourceAdminService;
    private final SchemaHasher schemaHasher;
    private final ObjectMapper objectMapper;

    @Override
    public void recordOnExecution(UUID queryRequestId) {
        try {
            if (repository.existsByQueryRequestId(queryRequestId)) {
                return;
            }
            var query = queryRequestLookupService.findById(queryRequestId).orElse(null);
            if (query == null) {
                log.warn("Snapshot skipped: query {} not found", queryRequestId);
                return;
            }
            var detail = queryRequestLookupService
                    .findDetailById(queryRequestId, query.organizationId())
                    .orElse(null);
            if (detail == null) {
                log.warn("Snapshot skipped: detail for query {} not found", queryRequestId);
                return;
            }
            var entity = build(query, detail);
            repository.save(entity);
        } catch (DataIntegrityViolationException ex) {
            // Lost the UNIQUE(query_request_id) race against a concurrent/redelivered event — fine.
            log.debug("Snapshot for query {} already recorded (unique race)", queryRequestId);
        } catch (RuntimeException ex) {
            log.error("Failed to record execution snapshot for query {}", queryRequestId, ex);
        }
    }

    @Override
    public Optional<QuerySnapshotView> find(UUID queryRequestId, UUID organizationId) {
        return repository.findByQueryRequestIdAndOrganizationId(queryRequestId, organizationId)
                .map(QuerySnapshotMapper::toView);
    }

    private QuerySnapshotEntity build(QueryRequestSnapshot query, QueryDetailView detail) {
        var entity = new QuerySnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(query.id());
        entity.setOrganizationId(query.organizationId());
        entity.setDatasourceId(query.datasourceId());
        entity.setSubmittedBy(query.submittedByUserId());
        entity.setSqlText(query.sqlText());
        entity.setQueryType(query.queryType());
        entity.setTransactional(query.transactional());
        entity.setDbType(detail.dbType());
        entity.setReferencedTables(referencedTables(query, detail));
        entity.setSchemaHash(computeSchemaHash(query));
        entity.setAiAnalysisJson(serialize(detail.aiAnalysis(), null));
        entity.setReviewDecisionsJson(serialize(
                detail.reviewDecisions() == null ? List.of() : detail.reviewDecisions(), "[]"));
        entity.setRowsAffected(detail.rowsAffected());
        entity.setExecutionDurationMs(detail.durationMs());
        entity.setExecutedAt(detail.updatedAt() != null ? detail.updatedAt() : Instant.now());
        return entity;
    }

    private String[] referencedTables(QueryRequestSnapshot query, QueryDetailView detail) {
        try {
            var parsed = queryParser.parse(query.sqlText(), detail.dbType());
            return parsed.referencedTables().toArray(String[]::new);
        } catch (RuntimeException ex) {
            log.debug("Could not re-parse SQL for snapshot of query {}; storing no referenced tables",
                    query.id());
            return new String[0];
        }
    }

    private String computeSchemaHash(QueryRequestSnapshot query) {
        try {
            var schema = datasourceAdminService.introspectSchemaForSystem(
                    query.datasourceId(), query.organizationId());
            return schemaHasher.hash(schema);
        } catch (RuntimeException ex) {
            log.debug("Could not introspect schema for snapshot of query {}; schema hash is null",
                    query.id());
            return null;
        }
    }

    private String serialize(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            log.debug("Could not serialize snapshot sub-document; using fallback", ex);
            return fallback;
        }
    }
}
