package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditChainVerificationSummary;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditLogVerificationResult;
import com.bablsoft.accessflow.audit.api.AuditLogView;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class DefaultAuditLogService implements AuditLogService {

    static final String REASON_ANCHOR_HAS_PREVIOUS = "anchor_has_previous";
    static final String REASON_NULL_HASH_IN_CHAIN = "null_hash_in_chain";
    static final String REASON_PREVIOUS_HASH_MISMATCH = "previous_hash_mismatch";
    static final String REASON_CURRENT_HASH_MISMATCH = "current_hash_mismatch";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditChainHasher hasher;
    private final JdbcTemplate auditJdbcTemplate;
    private final TransactionTemplate auditTransactionTemplate;

    DefaultAuditLogService(AuditLogRepository repository,
                           ObjectMapper objectMapper,
                           AuditChainHasher hasher,
                           @Qualifier("auditJdbcTemplate") JdbcTemplate auditJdbcTemplate,
                           @Qualifier("auditTransactionTemplate") TransactionTemplate auditTransactionTemplate) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
        this.auditJdbcTemplate = auditJdbcTemplate;
        this.auditTransactionTemplate = auditTransactionTemplate;
    }

    @Override
    public UUID record(AuditEntry entry) {
        var orgId = entry.organizationId();
        long lockKey = orgId.getMostSignificantBits() ^ orgId.getLeastSignificantBits();
        var id = UUID.randomUUID();
        // Postgres TIMESTAMPTZ stores microsecond precision; truncate so the value used in the
        // HMAC canonical form matches what verify() reads back from the database.
        var createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var metadataJson = serializeMetadata(entry.metadata());

        auditTransactionTemplate.executeWithoutResult(status -> {
            // pg_advisory_xact_lock returns void — wrap in a ResultSetExtractor so the
            // single-null-column response from PostgreSQL doesn't trip queryForObject's
            // EmptyResultDataAccessException check.
            auditJdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                    (ResultSetExtractor<Object>) rs -> null, lockKey);

            byte[] prevHash = auditJdbcTemplate.query(
                    "SELECT current_hash FROM audit_log WHERE organization_id = ? "
                            + "ORDER BY created_at DESC, id DESC LIMIT 1",
                    (ResultSetExtractor<byte[]>) rs -> rs.next() ? rs.getBytes(1) : null,
                    orgId);

            var row = new AuditLogEntity();
            row.setId(id);
            row.setOrganizationId(orgId);
            row.setActorId(entry.actorId());
            row.setAction(entry.action().name());
            row.setResourceType(entry.resourceType().dbValue());
            row.setResourceId(entry.resourceId());
            row.setMetadata(metadataJson);
            row.setIpAddress(entry.ipAddress());
            row.setUserAgent(entry.userAgent());
            row.setCreatedAt(createdAt);
            row.setPreviousHash(prevHash);
            row.setCurrentHash(hasher.hash(row, prevHash));

            auditJdbcTemplate.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO audit_log "
                                + "(id, organization_id, actor_id, action, resource_type, resource_id, "
                                + " metadata, ip_address, user_agent, created_at, previous_hash, current_hash) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, ?, ?, ?, ?)");
                ps.setObject(1, row.getId());
                ps.setObject(2, row.getOrganizationId());
                if (row.getActorId() == null) {
                    ps.setNull(3, Types.OTHER);
                } else {
                    ps.setObject(3, row.getActorId());
                }
                ps.setString(4, row.getAction());
                ps.setString(5, row.getResourceType());
                if (row.getResourceId() == null) {
                    ps.setNull(6, Types.OTHER);
                } else {
                    ps.setObject(6, row.getResourceId());
                }
                ps.setString(7, row.getMetadata());
                ps.setString(8, row.getIpAddress());
                ps.setString(9, row.getUserAgent());
                ps.setTimestamp(10, Timestamp.from(row.getCreatedAt()));
                if (row.getPreviousHash() == null) {
                    ps.setNull(11, Types.BINARY);
                } else {
                    ps.setBytes(11, row.getPreviousHash());
                }
                ps.setBytes(12, row.getCurrentHash());
                return ps;
            });
        });
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogView> query(UUID organizationId, AuditLogQuery filter,
                                            PageRequest pageRequest) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        var spec = AuditLogSpecifications.forQuery(organizationId,
                filter == null ? AuditLogQuery.empty() : filter);
        var page = repository.findAll(spec, toSpringPageable(pageRequest)).map(this::toView);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        var sort = request.sort().isEmpty()
                ? Sort.unsorted()
                : Sort.by(request.sort().stream().map(DefaultAuditLogService::toSpringOrder).toList());
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogVerificationResult verify(UUID organizationId, Instant from, Instant to) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        long rowsChecked = 0;
        byte[] expectedPrev = null;
        boolean anchored = false;
        var spec = AuditLogSpecifications.forVerification(organizationId, from, to);
        for (AuditLogEntity row : repository.findForVerification(spec)) {
            if (!anchored) {
                if (row.getCurrentHash() == null) {
                    // pre-chain row written before V26 — skip without counting
                    continue;
                }
                if (row.getPreviousHash() != null) {
                    return AuditLogVerificationResult.fail(
                            rowsChecked, row.getId(), row.getCreatedAt(),
                            REASON_ANCHOR_HAS_PREVIOUS);
                }
                var recomputed = hasher.hash(row, null);
                if (!Arrays.equals(recomputed, row.getCurrentHash())) {
                    return AuditLogVerificationResult.fail(
                            rowsChecked, row.getId(), row.getCreatedAt(),
                            REASON_CURRENT_HASH_MISMATCH);
                }
                expectedPrev = row.getCurrentHash();
                anchored = true;
                rowsChecked++;
                continue;
            }
            if (row.getCurrentHash() == null || row.getPreviousHash() == null) {
                return AuditLogVerificationResult.fail(
                        rowsChecked, row.getId(), row.getCreatedAt(),
                        REASON_NULL_HASH_IN_CHAIN);
            }
            if (!Arrays.equals(row.getPreviousHash(), expectedPrev)) {
                return AuditLogVerificationResult.fail(
                        rowsChecked, row.getId(), row.getCreatedAt(),
                        REASON_PREVIOUS_HASH_MISMATCH);
            }
            var recomputed = hasher.hash(row, row.getPreviousHash());
            if (!Arrays.equals(recomputed, row.getCurrentHash())) {
                return AuditLogVerificationResult.fail(
                        rowsChecked, row.getId(), row.getCreatedAt(),
                        REASON_CURRENT_HASH_MISMATCH);
            }
            expectedPrev = row.getCurrentHash();
            rowsChecked++;
        }
        return AuditLogVerificationResult.ok(rowsChecked);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditChainVerificationSummary> verifyAllOrganizations() {
        return repository.findDistinctOrganizationIds().stream()
                .map(orgId -> new AuditChainVerificationSummary(orgId, verify(orgId, null, null)))
                .toList();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(metadata);
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        var parsed = (Map<String, Object>) objectMapper.readValue(json, Map.class);
        return new LinkedHashMap<>(parsed);
    }

    private AuditLogView toView(AuditLogEntity entity) {
        return new AuditLogView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getActorId(),
                AuditAction.valueOf(entity.getAction()),
                AuditResourceType.fromDbValue(entity.getResourceType()),
                entity.getResourceId(),
                deserializeMetadata(entity.getMetadata()),
                entity.getIpAddress(),
                entity.getUserAgent(),
                entity.getCreatedAt());
    }
}
