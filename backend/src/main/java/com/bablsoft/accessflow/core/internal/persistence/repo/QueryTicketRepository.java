package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.QueryTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryTicketRepository extends JpaRepository<QueryTicketEntity, UUID> {

    boolean existsByChannelIdAndQueryRequestIdAndTriggerEvent(UUID channelId, UUID queryRequestId,
                                                              String triggerEvent);

    Optional<QueryTicketEntity> findByChannelIdAndExternalId(UUID channelId, String externalId);

    List<QueryTicketEntity> findAllByQueryRequestIdAndOrganizationIdOrderByCreatedAtAsc(
            UUID queryRequestId, UUID organizationId);
}
