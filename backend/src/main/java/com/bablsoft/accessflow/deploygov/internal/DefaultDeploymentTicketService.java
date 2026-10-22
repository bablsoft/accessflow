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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDeploymentTicketService implements DeploymentTicketService {

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
