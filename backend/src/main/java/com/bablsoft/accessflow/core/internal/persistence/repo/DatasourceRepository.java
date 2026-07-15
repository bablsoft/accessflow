package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DatasourceRepository extends JpaRepository<DatasourceEntity, UUID> {

    List<DatasourceEntity> findAllByOrganization_Id(UUID organizationId);

    Page<DatasourceEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    List<DatasourceEntity> findAllByOrganization_IdAndActiveTrue(UUID organizationId);

    long countByOrganization_Id(UUID organizationId);

    boolean existsByOrganization_Id(UUID organizationId);

    boolean existsByOrganization_IdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganization_IdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name,
                                                             UUID id);

    boolean existsByReviewPlan_Id(UUID reviewPlanId);

    boolean existsByCustomDriver_Id(UUID customDriverId);

    List<DatasourceEntity> findAllByCustomDriver_Id(UUID customDriverId);

    List<DatasourceEntity> findAllByAiConfigId(UUID aiConfigId);

    @Query("""
            select d.aiConfigId, count(d) from DatasourceEntity d
             where d.aiConfigId in :aiConfigIds
            group by d.aiConfigId
            """)
    List<Object[]> countByAiConfigIdIn(@Param("aiConfigIds") Collection<UUID> aiConfigIds);

    @Query("""
            select d from DatasourceEntity d
             where d.passwordEncrypted = :reference
                or d.readReplicaPasswordEncrypted = :reference
                or d.apiKeyEncrypted = :reference
            """)
    List<DatasourceEntity> findAllByCredentialReference(@Param("reference") String reference);

    @Query("""
            select distinct d.aiConfigId from DatasourceEntity d
             where d.organization.id = :orgId
               and d.aiAnalysisEnabled = true
               and d.active = true
               and d.aiConfigId is not null
            """)
    List<UUID> findActiveAiAnalysisAiConfigIdsByOrganization(@Param("orgId") UUID organizationId);

    @Query("""
            select d from DatasourceEntity d
             where d.organization.id = :orgId
               and (
                 exists (
                     select 1 from DatasourceUserPermissionEntity p
                      where p.datasource = d and p.user.id = :userId
                        and (p.expiresAt is null or p.expiresAt > :now))
                 or exists (
                     select 1 from DatasourceGroupPermissionEntity gp
                      where gp.datasource = d
                        and (gp.expiresAt is null or gp.expiresAt > :now)
                        and gp.group.id in (
                            select m.group.id from UserGroupMembershipEntity m
                             where m.user.id = :userId)))
            """)
    Page<DatasourceEntity> findAllVisibleToUser(@Param("orgId") UUID orgId,
                                                @Param("userId") UUID userId,
                                                @Param("now") Instant now,
                                                Pageable pageable);

    @Query("""
            select (count(d) > 0) from DatasourceEntity d
             where d.id = :datasourceId
               and (
                 exists (
                     select 1 from DatasourceUserPermissionEntity p
                      where p.datasource = d and p.user.id = :userId
                        and (p.expiresAt is null or p.expiresAt > :now))
                 or exists (
                     select 1 from DatasourceGroupPermissionEntity gp
                      where gp.datasource = d
                        and (gp.expiresAt is null or gp.expiresAt > :now)
                        and gp.group.id in (
                            select m.group.id from UserGroupMembershipEntity m
                             where m.user.id = :userId)))
            """)
    boolean existsVisibleToUser(@Param("datasourceId") UUID datasourceId,
                                @Param("userId") UUID userId,
                                @Param("now") Instant now);
}
