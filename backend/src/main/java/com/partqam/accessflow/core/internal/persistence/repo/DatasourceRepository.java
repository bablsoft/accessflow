package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DatasourceRepository extends JpaRepository<DatasourceEntity, UUID> {

    List<DatasourceEntity> findAllByOrganization_Id(UUID organizationId);

    Page<DatasourceEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    List<DatasourceEntity> findAllByOrganization_IdAndActiveTrue(UUID organizationId);

    boolean existsByOrganization_IdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganization_IdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name,
                                                             UUID id);

    @Query("""
            select d from DatasourceEntity d
             where d.organization.id = :orgId
               and exists (
                   select 1 from DatasourceUserPermissionEntity p
                    where p.datasource = d and p.user.id = :userId)
            """)
    Page<DatasourceEntity> findAllVisibleToUser(@Param("orgId") UUID orgId,
                                                @Param("userId") UUID userId,
                                                Pageable pageable);
}
