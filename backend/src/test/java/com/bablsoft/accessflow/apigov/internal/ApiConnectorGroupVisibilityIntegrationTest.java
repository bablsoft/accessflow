package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorGroupPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorGroupPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AF-558 parity for the API editor: a user granted a connector <em>only via a group</em> must see it
 * through {@code listForUser} / {@code getForUser}. Exercises the real resolver + repos against
 * Postgres so the group-membership visibility path can't silently regress.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorGroupVisibilityIntegrationTest {

    @Autowired ApiConnectorAdminService service;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    // These tests commit rows into the shared Testcontainers DB. The api_connector_group_permissions
    // .created_by FK to users has no ON DELETE, so leaving rows behind would make a later integration
    // test's userRepository.deleteAll() fail. Clear everything this test creates, children first.
    @AfterEach
    void cleanup() {
        groupPermissionRepository.deleteAll();
        membershipRepository.deleteAll();
        connectorRepository.deleteAll();
        userGroupRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listForUserIncludesConnectorVisibleViaGroupMembership() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var member = saveUser(org, UserRoleType.ANALYST);
        var connector = saveConnector(org);
        var group = saveGroup(org);
        addToGroup(member, group);
        saveGroupPermission(connector, group, admin, null);

        var page = service.listForUser(org.getId(), member.getId(), PageRequest.of(0, 20));

        assertThat(page.content()).extracting(v -> v.id()).contains(connector.getId());
    }

    @Test
    void getForUserReturnsConnectorVisibleViaGroupMembership() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var member = saveUser(org, UserRoleType.ANALYST);
        var connector = saveConnector(org);
        var group = saveGroup(org);
        addToGroup(member, group);
        saveGroupPermission(connector, group, admin, null);

        var view = service.getForUser(connector.getId(), org.getId(), member.getId());

        assertThat(view.id()).isEqualTo(connector.getId());
    }

    @Test
    void nonMemberDoesNotSeeGroupGrantedConnector() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var outsider = saveUser(org, UserRoleType.ANALYST);
        var connector = saveConnector(org);
        var group = saveGroup(org);
        saveGroupPermission(connector, group, admin, null);

        assertThat(service.listForUser(org.getId(), outsider.getId(), PageRequest.of(0, 20)).content())
                .isEmpty();
        assertThatThrownBy(() -> service.getForUser(connector.getId(), org.getId(), outsider.getId()))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void expiredGroupGrantIsNotVisible() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var member = saveUser(org, UserRoleType.ANALYST);
        var connector = saveConnector(org);
        var group = saveGroup(org);
        addToGroup(member, group);
        saveGroupPermission(connector, group, admin, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.listForUser(org.getId(), member.getId(), PageRequest.of(0, 20)).content())
                .isEmpty();
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("User");
        user.setPasswordHash("x");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private ApiConnectorEntity saveConnector(OrganizationEntity org) {
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(org.getId());
        c.setName("conn-" + UUID.randomUUID());
        c.setProtocol(ApiProtocol.REST);
        c.setBaseUrl("https://api.test");
        c.setActive(true);
        return connectorRepository.save(c);
    }

    private UserGroupEntity saveGroup(OrganizationEntity org) {
        var group = new UserGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganization(org);
        group.setName("Group-" + UUID.randomUUID());
        return userGroupRepository.save(group);
    }

    private void addToGroup(UserEntity user, UserGroupEntity group) {
        var membership = new UserGroupMembershipEntity();
        membership.setId(new UserGroupMembershipEntity.Id(user.getId(), group.getId()));
        membership.setUser(user);
        membership.setGroup(group);
        membershipRepository.save(membership);
    }

    private void saveGroupPermission(ApiConnectorEntity connector, UserGroupEntity group,
                                     UserEntity grantedBy, Instant expiresAt) {
        var perm = new ApiConnectorGroupPermissionEntity();
        perm.setId(UUID.randomUUID());
        perm.setOrganizationId(connector.getOrganizationId());
        perm.setConnectorId(connector.getId());
        perm.setGroupId(group.getId());
        perm.setCreatedBy(grantedBy.getId());
        perm.setCanRead(true);
        perm.setExpiresAt(expiresAt);
        groupPermissionRepository.save(perm);
    }
}
