package com.partqam.accessflow.audit.internal;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogQuery;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditLogView;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.partqam.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAuditLogService implements AuditLogService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public UUID record(AuditEntry entry) {
        var id = UUID.randomUUID();
        var row = new AuditLogEntity();
        row.setId(id);
        row.setOrganizationId(entry.organizationId());
        row.setActorId(entry.actorId());
        row.setAction(entry.action().name());
        row.setResourceType(entry.resourceType().dbValue());
        row.setResourceId(entry.resourceId());
        row.setMetadata(serializeMetadata(entry.metadata()));
        row.setIpAddress(entry.ipAddress());
        row.setUserAgent(entry.userAgent());
        row.setCreatedAt(Instant.now());
        repository.save(row);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogView> query(UUID organizationId, AuditLogQuery filter, Pageable pageable) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        var spec = AuditLogSpecifications.forQuery(organizationId,
                filter == null ? AuditLogQuery.empty() : filter);
        return repository.findAll(spec, pageable).map(this::toView);
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
