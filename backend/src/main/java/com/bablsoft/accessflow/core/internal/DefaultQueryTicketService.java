package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.QueryTicketView;
import com.bablsoft.accessflow.core.api.RecordTicketCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryTicketEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryTicketService implements QueryTicketService {

    private final QueryTicketRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public QueryTicketView recordCreated(RecordTicketCommand command) {
        var now = clock.instant();
        var entity = new QueryTicketEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setQueryRequestId(command.queryRequestId());
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
    public boolean existsFor(UUID channelId, UUID queryRequestId, String triggerEvent) {
        return repository.existsByChannelIdAndQueryRequestIdAndTriggerEvent(channelId,
                queryRequestId, triggerEvent);
    }

    @Override
    @Transactional
    public Optional<QueryTicketView> updateStatus(UUID channelId, String externalId, String status,
                                                  String resolution) {
        return repository.findByChannelIdAndExternalId(channelId, externalId)
                .map(entity -> {
                    entity.setStatus(status);
                    entity.setResolution(resolution);
                    entity.setUpdatedAt(clock.instant());
                    return toView(repository.save(entity));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueryTicketView> listByQueryRequest(UUID queryRequestId, UUID organizationId) {
        return repository
                .findAllByQueryRequestIdAndOrganizationIdOrderByCreatedAtAsc(queryRequestId,
                        organizationId)
                .stream()
                .map(DefaultQueryTicketService::toView)
                .toList();
    }

    private static QueryTicketView toView(QueryTicketEntity entity) {
        return new QueryTicketView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getQueryRequestId(),
                entity.getChannelId(),
                entity.getTicketSystem(),
                entity.getTriggerEvent(),
                entity.getExternalId(),
                entity.getExternalKey(),
                entity.getUrl(),
                entity.getStatus(),
                entity.getResolution(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
