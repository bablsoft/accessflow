package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The org-wide break-glass lookup against a real Postgres (#968): the derived query through
 * {@code datasource.organization}, the group row's own {@code organization_id}, and the expansion
 * through real membership rows.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class BreakGlassContributionLookupIntegrationTest {

    @Autowired DatasourceUserPermissionLookupService service;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired DatasourceGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;

    private final List<UUID> createdPermissionIds = new ArrayList<>();
    private final List<UUID> createdGroupPermissionIds = new ArrayList<>();
    private final List<UUID> createdGroupIds = new ArrayList<>();
    private final List<UUID> createdDatasourceIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdOrganizationIds = new ArrayList<>();

    private OrganizationEntity orgA;
    private DatasourceEntity dsA;
    private UserEntity directHolder;
    private UserEntity expiredHolder;
    private UserEntity ordinaryReader;
    private UserEntity memberOne;
    private UserEntity memberTwo;
    private UserEntity otherOrgHolder;
    private UserGroupEntity oncall;

    @BeforeEach
    void setUp() {
        orgA = saveOrg("bg-a");
        var orgB = saveOrg("bg-b");
        dsA = saveDatasource(orgA);
        var dsB = saveDatasource(orgB);
        directHolder = saveUser(orgA);
        expiredHolder = saveUser(orgA);
        ordinaryReader = saveUser(orgA);
        memberOne = saveUser(orgA);
        memberTwo = saveUser(orgA);
        otherOrgHolder = saveUser(orgB);
        oncall = saveGroup(orgA, "oncall-" + UUID.randomUUID());
        var farFuture = Instant.parse("2099-01-01T00:00:00Z");

        saveDirect(dsA, directHolder, true, farFuture);
        saveDirect(dsA, expiredHolder, true, Instant.now().minusSeconds(60));
        saveDirect(dsA, ordinaryReader, false, null);
        saveDirect(dsB, otherOrgHolder, true, null);
        saveGroupPermission(orgA, dsA, oncall, true, null);
        saveMembership(oncall, memberOne);
        saveMembership(oncall, memberTwo);
    }

    // Scoped to this class's rows: the Testcontainers database is shared across classes.
    @AfterEach
    void cleanup() {
        membershipRepository.deleteAll(createdGroupIds.stream()
                .flatMap(id -> membershipRepository.findAllByGroup_Id(id).stream()).toList());
        groupPermissionRepository.deleteAllById(createdGroupPermissionIds);
        permissionRepository.deleteAllById(createdPermissionIds);
        userGroupRepository.deleteAllById(createdGroupIds);
        datasourceRepository.deleteAllById(createdDatasourceIds);
        userRepository.deleteAllById(createdUserIds);
        organizationRepository.deleteAllById(createdOrganizationIds);
    }

    @Test
    void returnsUnexpiredDirectAndGroupBreakGlassRowsOfTheOrganizationOnly() {
        var contributions = service.findBreakGlassContributionsForOrganization(orgA.getId());

        assertThat(contributions).extracting(DatasourcePermissionContribution::userId)
                .containsExactlyInAnyOrder(directHolder.getId(), memberOne.getId(), memberTwo.getId())
                .doesNotContain(expiredHolder.getId(), ordinaryReader.getId(), otherOrgHolder.getId());
        assertThat(contributions).allSatisfy(c -> {
            assertThat(c.canBreakGlass()).isTrue();
            assertThat(c.datasourceId()).isEqualTo(dsA.getId());
        });
        var direct = contributions.stream()
                .filter(c -> c.userId().equals(directHolder.getId())).findFirst().orElseThrow();
        assertThat(direct.sourceKind()).isEqualTo(DatasourcePermissionSourceKind.DIRECT);
        assertThat(direct.expiresAt()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        var viaGroup = contributions.stream()
                .filter(c -> c.userId().equals(memberTwo.getId())).findFirst().orElseThrow();
        assertThat(viaGroup.sourceKind()).isEqualTo(DatasourcePermissionSourceKind.GROUP);
        assertThat(viaGroup.groupId()).isEqualTo(oncall.getId());
        assertThat(viaGroup.groupName()).isEqualTo(oncall.getName());
        assertThat(viaGroup.expiresAt()).isNull();
    }

    private void saveDirect(DatasourceEntity ds, UserEntity user, boolean breakGlass, Instant expiresAt) {
        var p = new DatasourceUserPermissionEntity();
        p.setId(UUID.randomUUID());
        p.setDatasource(ds);
        p.setUser(user);
        p.setCanRead(true);
        p.setCanBreakGlass(breakGlass);
        p.setExpiresAt(expiresAt);
        p.setCreatedBy(user);
        createdPermissionIds.add(p.getId());
        permissionRepository.save(p);
    }

    private void saveGroupPermission(OrganizationEntity org, DatasourceEntity ds, UserGroupEntity group,
                                     boolean breakGlass, Instant expiresAt) {
        var p = new DatasourceGroupPermissionEntity();
        p.setId(UUID.randomUUID());
        p.setOrganizationId(org.getId());
        p.setDatasource(ds);
        p.setGroup(group);
        p.setCanRead(true);
        p.setCanBreakGlass(breakGlass);
        p.setExpiresAt(expiresAt);
        p.setCreatedBy(directHolder);
        createdGroupPermissionIds.add(p.getId());
        groupPermissionRepository.save(p);
    }

    private void saveMembership(UserGroupEntity group, UserEntity user) {
        var m = new UserGroupMembershipEntity();
        m.setUser(user);
        m.setGroup(group);
        membershipRepository.save(m);
    }

    private UserGroupEntity saveGroup(OrganizationEntity org, String name) {
        var g = new UserGroupEntity();
        g.setId(UUID.randomUUID());
        g.setOrganization(org);
        g.setName(name);
        createdGroupIds.add(g.getId());
        return userGroupRepository.save(g);
    }

    private OrganizationEntity saveOrg(String name) {
        var o = new OrganizationEntity();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setSlug(name + "-" + UUID.randomUUID());
        createdOrganizationIds.add(o.getId());
        return organizationRepository.save(o);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("u-" + UUID.randomUUID() + "@example.com");
        u.setDisplayName("User");
        u.setPasswordHash("hashed");
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        createdUserIds.add(u.getId());
        return userRepository.save(u);
    }

    private DatasourceEntity saveDatasource(OrganizationEntity org) {
        var d = new DatasourceEntity();
        d.setId(UUID.randomUUID());
        d.setOrganization(org);
        d.setName("bg-ds-" + UUID.randomUUID());
        d.setDbType(DbType.POSTGRESQL);
        d.setHost("h");
        d.setPort(5432);
        d.setDatabaseName("db");
        d.setUsername("u");
        d.setPasswordEncrypted("ENC");
        d.setAiAnalysisEnabled(false);
        d.setActive(true);
        d.setCreatedAt(Instant.now());
        createdDatasourceIds.add(d.getId());
        return datasourceRepository.save(d);
    }
}
