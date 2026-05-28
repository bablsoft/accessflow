package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroupEntity, UUID> {

    Page<UserGroupEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    List<UserGroupEntity> findAllByOrganization_IdAndIdIn(UUID organizationId, List<UUID> ids);

    @Query("select g from UserGroupEntity g where g.organization.id = :organizationId "
            + "and lower(g.name) = lower(:name)")
    Optional<UserGroupEntity> findByOrganizationIdAndNameIgnoreCase(
            @Param("organizationId") UUID organizationId, @Param("name") String name);
}
