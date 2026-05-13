package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
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
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAuditLogService implements AuditLogService {

    static final String REASON_ANCHOR_HAS_PREVIOUS = "anchor_has_previous";
    static final String REASON_NULL_HASH_IN_CHAIN = "null_hash_in_chain";
    static final String REASON_PREVIOUS_HASH_MISMATCH = "previous_hash_mismatch";
    static final String REASON_CURRENT_HASH_MISMATCH = "current_hash_mismatch";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditChainHasher hasher;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public UUID record(AuditEntry entry) {
        var orgId = entry.organizationId();
        long lockKey = orgId.getMostSignificantBits() ^ orgId.getLeastSignificantBits();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, lockKey)
                .getSingleResult();

        var prev = repository
                .findTopByOrganizationIdOrderByCreatedAtDescIdDesc(orgId)
                .orElse(null);
        byte[] prevHash = prev == null ? null : prev.getCurrentHash();

        var id = UUID.randomUUID();
        var row = new AuditLogEntity();
        row.setId(id);
        row.setOrganizationId(orgId);
        row.setActorId(entry.actorId());
        row.setAction(entry.action().name());
        row.setResourceType(entry.resourceType().dbValue());
        row.setResourceId(entry.resourceId());
        row.setMetadata(serializeMetadata(entry.metadata()));
        row.setIpAddress(entry.ipAddress());
        row.setUserAgent(entry.userAgent());
        // Postgres TIMESTAMPTZ stores microsecond precision; truncate so the value used in the
        // HMAC canonical form matches what verify() reads back from the database.
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        row.setPreviousHash(prevHash);
        row.setCurrentHash(hasher.hash(row, prevHash));
        repository.save(row);
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
