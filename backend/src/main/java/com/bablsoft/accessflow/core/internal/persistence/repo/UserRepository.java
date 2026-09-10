package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.Permission;
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

    Optional<UserEntity> findByOrganization_IdAndId(UUID organizationId, UUID id);

    Optional<UserEntity> findByOrganization_IdAndEmail(UUID organizationId, String email);

    Optional<UserEntity> findByOrganization_IdAndScimExternalId(
            UUID organizationId, String scimExternalId);

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

    /**
     * Active users in the organization whose effective role grants a permission (AF-859), matched
     * three ways because a role can be recorded three ways: an assigned system role row (by name),
     * a legacy enum column on a row not yet linked to a role, and a custom role with its own
     * {@code role_permissions} rows. The two system-role parameters are derived in Java from
     * {@code SystemRolePermissions}, which is the authoritative map — resolving them in SQL against
     * the {@code V114} seed rows instead would let the query drift from what actually authorizes a
     * request.
     */
    @Query("""
            select distinct u.id from UserEntity u left join u.roleRef r
            where u.organization.id = :organizationId
              and u.active = true
              and ((r is not null and r.system = true and r.name in :systemRoleNames)
                or (r is null and u.role in :systemRoles)
                or (r is not null and r.system = false and exists (
                        select 1 from RolePermissionEntity rp
                        where rp.role = r and rp.permission = :permission)))
            """)
    List<UUID> findUserIdsWithPermission(@Param("organizationId") UUID organizationId,
                                         @Param("permission") Permission permission,
                                         @Param("systemRoleNames") Collection<String> systemRoleNames,
                                         @Param("systemRoles") Collection<UserRoleType> systemRoles);

    /**
     * The custom-role half of {@link #findUserIdsWithPermission} on its own, for a permission that
     * no system role grants (AF-859). A separate query rather than an empty {@code IN} list, which
     * is not portable and would silently match either everything or nothing.
     */
    @Query("""
            select distinct u.id from UserEntity u join u.roleRef r
            where u.organization.id = :organizationId
              and u.active = true
              and r.system = false
              and exists (select 1 from RolePermissionEntity rp
                          where rp.role = r and rp.permission = :permission)
            """)
    List<UUID> findUserIdsWithCustomRolePermission(@Param("organizationId") UUID organizationId,
                                                   @Param("permission") Permission permission);
}
