package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentTicketService;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketView;
import com.bablsoft.accessflow.deploygov.api.RecordDeploymentTicketCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentTicketEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDeploymentTicketService implements DeploymentTicketService {

    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final DeploymentTicketRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public DeploymentTicketView recordCreated(RecordDeploymentTicketCommand command) {
        var now = clock.instant();
        var entity = new DeploymentTicketEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDeploymentRequestId(command.deploymentRequestId());
        entity.setChannelId(command.channelId());
        entity.setTicketSystem(command.ticketSystem());
        entity.setTriggerEvent(command.triggerEvent());
        entity.setExternalId(command.externalId());
        entity.setExternalKey(command.externalKey());
        entity.setUrl(command.url());
        entity.setStatus(command.status());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toView(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsFor(UUID channelId, UUID deploymentRequestId) {
        return repository.existsByChannelIdAndDeploymentRequestId(channelId, deploymentRequestId);
    }

    @Override
    @Transactional
    public Optional<DeploymentTicketView> updateStatus(UUID channelId, String externalId,
                                                       String status, String resolution,
                                                       String decision) {
        return repository.findByChannelIdAndExternalId(channelId, externalId)
                .map(entity -> {
                    var now = clock.instant();
                    entity.setStatus(status);
                    entity.setResolution(resolution);
                    if (decision == null && APPROVED.equals(entity.getDecision())) {
                        // Sticky approval (#993): a change keeps moving after CAB approval
                        // (Scheduled → Implement → Closed), and the gate must not close behind it.
                        entity.setUpdatedAt(now);
                        return toView(repository.save(entity));
                    }
                    if (decision == null) {
                        entity.setDecidedAt(null);
                    } else if (!Objects.equals(decision, entity.getDecision())) {
                        entity.setDecidedAt(now);
                    }
                    entity.setDecision(decision);
                    entity.setUpdatedAt(now);
                    return toView(repository.save(entity));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentTicketView> listByDeploymentRequest(UUID deploymentRequestId,
                                                              UUID organizationId) {
        return repository
                .findAllByDeploymentRequestIdAndOrganizationIdOrderByCreatedAtAsc(
                        deploymentRequestId, organizationId)
                .stream()
                .map(DefaultDeploymentTicketService::toView)
                .toList();
    }

    /**
     * The ticket that explains a request's change-ticket state — the newest rejected one (it blocks
     * the gate), else the newest approved one, else the newest linked ticket, else null. Shared by
     * the gate (#993) and the request list/detail views (#994).
     */
    static DeploymentTicketView representative(List<DeploymentTicketEntity> tickets) {
        var newestFirst = Comparator.comparing(DeploymentTicketEntity::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
        return tickets.stream()
                .filter(t -> REJECTED.equals(t.getDecision()))
                .min(newestFirst)
                .or(() -> tickets.stream().filter(t -> APPROVED.equals(t.getDecision()))
                        .min(newestFirst))
                .or(() -> tickets.stream().min(newestFirst))
                .map(DefaultDeploymentTicketService::toView)
                .orElse(null);
    }

    static DeploymentTicketView toView(DeploymentTicketEntity entity) {
        return new DeploymentTicketView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getDeploymentRequestId(),
                entity.getChannelId(),
                entity.getTicketSystem(),
                entity.getTriggerEvent(),
                entity.getExternalId(),
                entity.getExternalKey(),
                entity.getUrl(),
                entity.getStatus(),
                entity.getResolution(),
                entity.getDecision(),
                entity.getDecidedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
