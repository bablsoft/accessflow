package com.bablsoft.accessflow.access.internal.persistence.repo;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GrantUsageSummaryRepository extends JpaRepository<GrantUsageSummaryEntity, UUID> {

    Optional<GrantUsageSummaryEntity>
        findByOrganizationIdAndResourceKindAndResourceIdAndUserId(
            UUID organizationId, GrantResourceKind resourceKind, UUID resourceId, UUID userId);

    List<GrantUsageSummaryEntity> findByOrganizationId(UUID organizationId);

    List<GrantUsageSummaryEntity> findByOrganizationIdAndResourceKind(
            UUID organizationId, GrantResourceKind resourceKind);

    /**
     * The over-provisioned report page. Enum filters are bound as parameters rather than written as
     * inline JPQL literals — Hibernate casts an inline enum literal to a type named after the Java
     * class, which does not exist as a PG type, and the query fails at runtime on the enum columns.
     *
     * <p>Each filter is applied only when supplied; {@code recommendations} uses a separate emptiness
     * flag because an empty {@code IN ()} list is not valid SQL.
     */
    @Query("select s from GrantUsageSummaryEntity s where s.organizationId = :orgId "
            + "and (:resourceKind is null or s.resourceKind = :resourceKind) "
            + "and (:allRecommendations = true or s.recommendation in :recommendations) "
            + "and (:resourceId is null or s.resourceId = :resourceId) "
            + "and (:userId is null or s.userId = :userId)")
    Page<GrantUsageSummaryEntity> report(
            @Param("orgId") UUID organizationId,
            @Param("resourceKind") GrantResourceKind resourceKind,
            @Param("allRecommendations") boolean allRecommendations,
            @Param("recommendations") Collection<GrantUsageRecommendation> recommendations,
            @Param("resourceId") UUID resourceId,
            @Param("userId") UUID userId,
            Pageable pageable);
}
