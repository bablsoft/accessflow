package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRoleAndActive(UserRoleType role, boolean active);

    long countByOrganization_IdAndActiveTrue(UUID organizationId);

    List<UserEntity> findAllByOrganization_Id(UUID organizationId);

    Page<UserEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    List<UserEntity> findAllByOrganization_IdAndRole(UUID organizationId, UserRoleType role);

    List<UserEntity> findAllByOrganization_IdAndIdIn(UUID organizationId, Collection<UUID> ids);

    long countByRoleRef_Id(UUID roleId);

    /**
     * Users in the organization whose effective role NAME matches (AF-522): the assigned role
     * row's name, or — for rows not yet linked to a role (mid-rolling-deploy) — the legacy enum
     * column when the name is a system-role name ({@code systemRole} non-null).
     */
    @Query("""
            select u from UserEntity u left join u.roleRef r
            where u.organization.id = :organizationId
              and (lower(r.name) = lower(:roleName) or (r is null and u.role = :systemRole))
            """)
    List<UserEntity> findAllByOrganizationAndRoleName(@Param("organizationId") UUID organizationId,
                                                      @Param("roleName") String roleName,
                                                      @Param("systemRole") UserRoleType systemRole);
}
