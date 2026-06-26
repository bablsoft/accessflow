package com.bablsoft.accessflow.attestation.internal.persistence.repo;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttestationItemRepository
        extends JpaRepository<AttestationItemEntity, UUID>,
                JpaSpecificationExecutor<AttestationItemEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from AttestationItemEntity i where i.id = :id")
    Optional<AttestationItemEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<AttestationItemEntity> findByCampaignId(UUID campaignId, Pageable pageable);

    List<AttestationItemEntity> findByCampaignIdAndDecision(UUID campaignId,
                                                            AttestationItemDecision decision);

    List<AttestationItemEntity> findByCampaignIdOrderByCreatedAtAsc(UUID campaignId);

    long countByCampaignIdAndDecision(UUID campaignId, AttestationItemDecision decision);

    boolean existsByCampaignId(UUID campaignId);

    boolean existsByCampaignIdAndPermissionId(UUID campaignId, UUID permissionId);

    /**
     * Items in a given decision state belonging to campaigns in a given status in the organization —
     * the reviewer worklist (pass OPEN + PENDING) before per-item eligibility + self-review filtering.
     * Implicit cross join (the FK lives in {@code campaign_id}; campaigns and items have no JPA
     * association). The status/decision are bound parameters, not inline enum literals — Hibernate
     * casts an inline PG-enum literal to a type named after the Java class (which does not exist),
     * whereas a bound parameter is cast correctly by {@code PostgreSQLEnumJdbcType}.
     */
    @Query(value = "select i from AttestationItemEntity i, AttestationCampaignEntity c "
            + "where i.campaignId = c.id and c.organizationId = :orgId "
            + "and c.status = :campaignStatus and i.decision = :itemDecision",
            countQuery = "select count(i) from AttestationItemEntity i, AttestationCampaignEntity c "
            + "where i.campaignId = c.id and c.organizationId = :orgId "
            + "and c.status = :campaignStatus and i.decision = :itemDecision")
    Page<AttestationItemEntity> findItemsByCampaignStatusAndDecision(
            @Param("orgId") UUID orgId,
            @Param("campaignStatus") AttestationCampaignStatus campaignStatus,
            @Param("itemDecision") AttestationItemDecision itemDecision,
            Pageable pageable);

    @Query("select distinct i.datasourceId from AttestationItemEntity i where i.campaignId = :id")
    List<UUID> findDistinctDatasourceIdsByCampaignId(@Param("id") UUID campaignId);
}
