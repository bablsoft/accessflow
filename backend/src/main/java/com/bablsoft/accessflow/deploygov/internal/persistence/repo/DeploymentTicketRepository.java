package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentTicketRepository extends JpaRepository<DeploymentTicketEntity, UUID> {

    boolean existsByChannelIdAndDeploymentRequestId(UUID channelId, UUID deploymentRequestId);

    Optional<DeploymentTicketEntity> findByChannelIdAndExternalId(UUID channelId, String externalId);

    List<DeploymentTicketEntity> findAllByDeploymentRequestIdAndOrganizationIdOrderByCreatedAtAsc(
            UUID deploymentRequestId, UUID organizationId);

    List<DeploymentTicketEntity> findAllByDeploymentRequestId(UUID deploymentRequestId);

    List<DeploymentTicketEntity> findAllByDeploymentRequestIdIn(Collection<UUID> deploymentRequestIds);
}
