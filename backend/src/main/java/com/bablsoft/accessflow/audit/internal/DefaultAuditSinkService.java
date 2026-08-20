package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditSinkNameConflictException;
import com.bablsoft.accessflow.audit.api.AuditSinkNotFoundException;
import com.bablsoft.accessflow.audit.api.AuditSinkService;
import com.bablsoft.accessflow.audit.api.AuditSinkTestFailedException;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.api.AuditSinkView;
import com.bablsoft.accessflow.audit.api.CreateAuditSinkCommand;
import com.bablsoft.accessflow.audit.api.UpdateAuditSinkCommand;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditSinkRepository;
import com.bablsoft.accessflow.audit.internal.sink.AuditExportEvent;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDeliverer;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin CRUD over {@code audit_sinks} (#628). Config encryption/masking is delegated to
 * {@link AuditSinkConfigCodec}; delivery health is read straight off the row, plus a capped
 * keyset backlog count so the admin page can show how far each sink lags.
 */
@Service
@Slf4j
class DefaultAuditSinkService implements AuditSinkService {

    /** Backlog counting stops here; the view flags the count as capped ("1000+"). */
    static final int BEHIND_COUNT_CAP = 1000;

    static final String TEST_ACTION = "AUDIT_SINK_TEST";

    private final AuditSinkRepository sinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditSinkConfigCodec codec;
    private final Clock clock;
    private final Map<AuditSinkType, AuditSinkDeliverer> deliverers;

    DefaultAuditSinkService(AuditSinkRepository sinkRepository,
                            AuditLogRepository auditLogRepository,
                            AuditSinkConfigCodec codec,
                            Clock clock,
                            List<AuditSinkDeliverer> delivererList) {
        this.sinkRepository = sinkRepository;
        this.auditLogRepository = auditLogRepository;
        this.codec = codec;
        this.clock = clock;
        this.deliverers = delivererList.stream().collect(Collectors.toMap(
                AuditSinkDeliverer::type, Function.identity(),
                (a, b) -> a, () -> new EnumMap<>(AuditSinkType.class)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditSinkView> list(UUID organizationId) {
        return sinkRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public AuditSinkView create(CreateAuditSinkCommand command) {
        if (sinkRepository.existsByOrganizationIdAndName(command.organizationId(),
                command.name())) {
            throw new AuditSinkNameConflictException(command.name());
        }
        var entity = new AuditSinkEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setName(command.name());
        entity.setType(command.type());
        entity.setConfigJson(codec.encodeForPersistence(command.type(), command.config()));
        // Cursor stays at the epoch floor: a new sink backfills the full history. The external
        // copy is only chain-verifiable from its anchor, so starting mid-chain would leave
        // previous_hash pointers dangling outside the archive.
        return toView(sinkRepository.save(entity));
    }

    @Override
    @Transactional
    public AuditSinkView update(UUID id, UUID organizationId, UpdateAuditSinkCommand command) {
        var entity = load(id, organizationId);
        if (command.name() != null && !command.name().equals(entity.getName())) {
            if (sinkRepository.existsByOrganizationIdAndName(organizationId, command.name())) {
                throw new AuditSinkNameConflictException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.config() != null && !command.config().isEmpty()) {
            entity.setConfigJson(codec.mergeForPersistence(
                    entity.getType(), entity.getConfigJson(), command.config()));
        }
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        return toView(sinkRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = load(id, organizationId);
        sinkRepository.delete(entity);
    }

    @Override
    public void sendTest(UUID id, UUID organizationId) {
        var entity = load(id, organizationId);
        var deliverer = deliverers.get(entity.getType());
        if (deliverer == null) {
            throw new IllegalStateException(
                    "No deliverer registered for audit sink type " + entity.getType());
        }
        try {
            deliverer.deliverTest(entity, testEvent(organizationId));
        } catch (AuditSinkDeliveryException ex) {
            throw new AuditSinkTestFailedException(ex.getMessage(), ex);
        }
    }

    private AuditExportEvent testEvent(UUID organizationId) {
        return new AuditExportEvent(
                UUID.randomUUID(),
                organizationId,
                null,
                TEST_ACTION,
                "audit_sink",
                null,
                "{\"test\":true}",
                null,
                null,
                clock.instant(),
                null,
                null);
    }

    private AuditSinkEntity load(UUID id, UUID organizationId) {
        return sinkRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new AuditSinkNotFoundException(id));
    }

    private AuditSinkView toView(AuditSinkEntity entity) {
        var backlogIds = auditLogRepository.findIdsAfterKeyset(
                entity.getOrganizationId(), entity.getCursorCreatedAt(), entity.getCursorId(),
                PageRequest.of(0, BEHIND_COUNT_CAP + 1));
        boolean capped = backlogIds.size() > BEHIND_COUNT_CAP;
        long behindCount = capped ? BEHIND_COUNT_CAP : backlogIds.size();
        return new AuditSinkView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getType(),
                entity.getName(),
                codec.decodeForApi(entity.getConfigJson()),
                entity.isEnabled(),
                entity.getCursorCreatedAt(),
                entity.getLastSuccessAt(),
                entity.getLastError(),
                entity.getConsecutiveFailures(),
                entity.getNextAttemptAt(),
                behindCount,
                capped,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
