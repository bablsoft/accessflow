package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    /** Roles visible to an organization: the global system roles plus the org's custom roles. */
    @Query("""
            select r from RoleEntity r
            where r.organization is null or r.organization.id = :organizationId
            order by r.system desc, r.name asc
            """)
    List<RoleEntity> findAllInScope(@Param("organizationId") UUID organizationId);

    @Query("""
            select r from RoleEntity r
            where r.id = :id and (r.organization is null or r.organization.id = :organizationId)
            """)
    Optional<RoleEntity> findByIdInScope(@Param("id") UUID id,
                                         @Param("organizationId") UUID organizationId);

    @Query("""
            select r from RoleEntity r
            where lower(r.name) = lower(:name)
              and (r.organization is null or r.organization.id = :organizationId)
            """)
    Optional<RoleEntity> findByNameInScope(@Param("organizationId") UUID organizationId,
                                           @Param("name") String name);

    @Query("""
            select (count(r) > 0) from RoleEntity r
            where lower(r.name) = lower(:name) and r.id <> :id
              and (r.organization is null or r.organization.id = :organizationId)
            """)
    boolean existsByNameInScopeAndIdNot(@Param("organizationId") UUID organizationId,
                                        @Param("name") String name,
                                        @Param("id") UUID id);

    Optional<RoleEntity> findByNameAndSystemTrue(String name);
}
