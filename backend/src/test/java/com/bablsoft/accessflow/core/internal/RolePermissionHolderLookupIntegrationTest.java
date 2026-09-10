package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission-holder JPQL against a real Postgres. It has to run here rather than as a unit test:
 * the query mixes an enum-typed {@code IN} list, a string {@code IN} list and a correlated
 * {@code exists} over a composite-key entity, and none of that is exercised by mocking the
 * repository.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RolePermissionHolderLookupIntegrationTest {

    @Autowired RolePermissionHolderLookupService service;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdRoleIds = new ArrayList<>();
    private final List<UUID> createdOrganizationIds = new ArrayList<>();

    private OrganizationEntity organization;
    private UUID legacyAdminId;
    private UUID legacyAnalystId;
    private UUID inactiveAdminId;
    private UUID customRoleHolderId;
    private UUID customRoleNonHolderId;
    private UUID customRoleId;
    private UUID otherOrgAdminId;

    @BeforeEach
    void setUp() {
        organization = organization("primary");
        var otherOrganization = organization("other");

        legacyAdminId = user(organization, UserRoleType.ADMIN, true, null);
        legacyAnalystId = user(organization, UserRoleType.ANALYST, true, null);
        inactiveAdminId = user(organization, UserRoleType.ADMIN, false, null);
        otherOrgAdminId = user(otherOrganization, UserRoleType.ADMIN, true, null);

        var custom = new RoleEntity();
        custom.setId(UUID.randomUUID());
        custom.setOrganization(organization);
        custom.setName("Data steward " + UUID.randomUUID());
        custom.setSystem(false);
        roleRepository.save(custom);
        createdRoleIds.add(custom.getId());
        customRoleId = custom.getId();
        rolePermissionRepository.save(
                new RolePermissionEntity(custom, Permission.DATASOURCE_PERMISSION_MANAGE));
        customRoleHolderId = user(organization, null, true, custom);

        var otherCustom = new RoleEntity();
        otherCustom.setId(UUID.randomUUID());
        otherCustom.setOrganization(organization);
        otherCustom.setName("Intern " + UUID.randomUUID());
        otherCustom.setSystem(false);
        roleRepository.save(otherCustom);
        createdRoleIds.add(otherCustom.getId());
        customRoleNonHolderId = user(organization, null, true, otherCustom);
    }

    // Scoped to the rows this class created rather than deleteAll(): the Testcontainers database is
    // shared across integration test classes, and a blanket user delete trips the created_by FK on
    // whatever another class left behind.
    @AfterEach
    void cleanup() {
        rolePermissionRepository.deleteAll(rolePermissionRepository.findAll().stream()
                .filter(rp -> createdRoleIds.contains(rp.getRole().getId()))
                .toList());
        userRepository.deleteAllById(createdUserIds);
        roleRepository.deleteAllById(createdRoleIds);
        organizationRepository.deleteAllById(createdOrganizationIds);
    }

    @Test
    void findsLegacySystemRoleHoldersAndSkipsRolesThatDoNotGrantIt() {
        var holders = service.findUserIdsWithPermission(organization.getId(),
                Permission.QUERY_ADMIN);

        assertThat(holders).contains(legacyAdminId).doesNotContain(legacyAnalystId);
    }

    @Test
    void findsCustomRoleHoldersThroughTheirOwnPermissionRows() {
        var holders = service.findUserIdsWithPermission(organization.getId(),
                Permission.DATASOURCE_PERMISSION_MANAGE);

        assertThat(holders).contains(customRoleHolderId).doesNotContain(customRoleNonHolderId);
    }

    @Test
    void excludesInactiveUsersAndOtherOrganizations() {
        var holders = service.findUserIdsWithPermission(organization.getId(),
                Permission.QUERY_ADMIN);

        assertThat(holders).doesNotContain(inactiveAdminId, otherOrgAdminId);
    }

    @Test
    void aPermissionOnNoCustomRoleReturnsOnlySystemRoleHolders() {
        var holders = service.findUserIdsWithPermission(organization.getId(),
                Permission.QUERY_ADMIN);

        assertThat(holders).doesNotContain(customRoleHolderId, customRoleNonHolderId);
        assertThat(customRoleId).isNotNull();
    }

    private OrganizationEntity organization(String name) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(name + "-" + UUID.randomUUID());
        createdOrganizationIds.add(entity.getId());
        return organizationRepository.save(entity);
    }

    private UUID user(OrganizationEntity org, UserRoleType role, boolean active, RoleEntity roleRef) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("u-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("User");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setRoleRef(roleRef);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(active);
        user.setOrganization(org);
        createdUserIds.add(user.getId());
        return userRepository.save(user).getId();
    }
}
