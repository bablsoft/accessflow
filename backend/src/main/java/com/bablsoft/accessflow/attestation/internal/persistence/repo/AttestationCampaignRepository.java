package com.bablsoft.accessflow.attestation.internal.persistence.repo;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttestationCampaignRepository
        extends JpaRepository<AttestationCampaignEntity, UUID>,
                JpaSpecificationExecutor<AttestationCampaignEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AttestationCampaignEntity c where c.id = :id")
    Optional<AttestationCampaignEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<AttestationCampaignEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<AttestationCampaignEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<AttestationCampaignEntity> findByOrganizationIdAndStatus(
            UUID organizationId, AttestationCampaignStatus status, Pageable pageable);

    @Query("select c.id from AttestationCampaignEntity c "
            + "where c.status = :status and c.scheduledOpenAt <= :now")
    List<UUID> findIdsByStatusAndScheduledOpenAtBefore(
            @Param("status") AttestationCampaignStatus status, @Param("now") Instant now);

    @Query("select c.id from AttestationCampaignEntity c "
            + "where c.status = :status and c.dueAt <= :now")
    List<UUID> findIdsByStatusAndDueAtBefore(
            @Param("status") AttestationCampaignStatus status, @Param("now") Instant now);
}
