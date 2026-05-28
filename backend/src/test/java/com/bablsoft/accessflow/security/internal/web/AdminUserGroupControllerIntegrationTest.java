package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipSource;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminUserGroupControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OAuth2ConfigRepository oauth2ConfigRepository;
    @Autowired SamlConfigRepository samlConfigRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity reviewerUser;
    private UserEntity stranger;
    private String adminToken;
    private String analystToken;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        // Defensive cleanup matching AdminUserControllerIntegrationTest.
        membershipRepository.deleteAll();
        userGroupRepository.deleteAll();
        datasourceRepository.deleteAll();
        oauth2ConfigRepository.deleteAll();
        samlConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary-groups");
        otherOrg = saveOrg("Other", "other-groups");

        admin = saveUser(primaryOrg, "admin@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst@example.com", "Analyst", UserRoleType.ANALYST);
        reviewerUser = saveUser(primaryOrg, "rev@example.com", "Rev", UserRoleType.REVIEWER);
        stranger = saveUser(otherOrg, "stranger@example.com", "Stranger", UserRoleType.ANALYST);

        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @Test
    void listGroupsReturnsEmptyPageWhenNoneExist() {
        var result = mvc.get().uri("/api/v1/admin/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(0);
    }

    @Test
    void listGroupsRejectsNonAdmin() {
        var result = mvc.get().uri("/api/v1/admin/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void listGroupsRequiresAuth() {
        var result = mvc.get().uri("/api/v1/admin/groups").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void createGroupReturns201WithLocationHeader() throws Exception {
        var result = mvc.post().uri("/api/v1/admin/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Billing Reviewers","description":"Reviews billing queries"}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.name").asString()
                .isEqualTo("Billing Reviewers");
        assertThat(result).bodyJson().extractingPath("$.member_count").asNumber().isEqualTo(0);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/api/v1/admin/groups/");
    }

    @Test
    void createGroupRejectsDuplicateNameCaseInsensitive() {
        saveGroup("Eng");

        var result = mvc.post().uri("/api/v1/admin/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"ENG"}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("USER_GROUP_NAME_ALREADY_EXISTS");
    }

    @Test
    void createGroupValidatesName() {
        var result = mvc.post().uri("/api/v1/admin/groups")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":""}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getGroupReturnsCurrentMemberCount() {
        var group = saveGroup("Reviewers");
        saveMembership(group, reviewerUser, UserGroupMembershipSource.MANUAL);

        var result = mvc.get().uri("/api/v1/admin/groups/" + group.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("Reviewers");
        assertThat(result).bodyJson().extractingPath("$.member_count").asNumber().isEqualTo(1);
    }

    @Test
    void getGroupFromOtherOrgReturns404() {
        var foreignGroup = saveGroupForOrg(otherOrg, "Foreign");

        var result = mvc.get().uri("/api/v1/admin/groups/" + foreignGroup.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("USER_GROUP_NOT_FOUND");
    }

    @Test
    void updateGroupAppliesNameAndDescription() {
        var group = saveGroup("Old");

        var result = mvc.put().uri("/api/v1/admin/groups/" + group.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"New","description":"updated"}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("New");
        assertThat(result).bodyJson().extractingPath("$.description").asString()
                .isEqualTo("updated");
    }

    @Test
    void updateGroupRejectsConflictingRename() {
        saveGroup("Other");
        var group = saveGroup("Eng");

        var result = mvc.put().uri("/api/v1/admin/groups/" + group.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Other"}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
    }

    @Test
    void deleteGroupReturns204() {
        var group = saveGroup("Doomed");

        var result = mvc.delete().uri("/api/v1/admin/groups/" + group.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(userGroupRepository.findById(group.getId())).isEmpty();
    }

    @Test
    void deleteGroupFromOtherOrgReturns404() {
        var foreign = saveGroupForOrg(otherOrg, "Foreign");

        var result = mvc.delete().uri("/api/v1/admin/groups/" + foreign.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void addMemberReturns201AndPersistsMembership() {
        var group = saveGroup("Reviewers");

        var result = mvc.post().uri("/api/v1/admin/groups/" + group.getId() + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s"}
                        """.formatted(reviewerUser.getId()))
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.email").asString()
                .isEqualTo("rev@example.com");
        assertThat(result).bodyJson().extractingPath("$.source").asString().isEqualTo("MANUAL");
        assertThat(membershipRepository.existsByUser_IdAndGroup_Id(reviewerUser.getId(),
                group.getId())).isTrue();
    }

    @Test
    void addMemberRejectsUserFromOtherOrg() {
        var group = saveGroup("Reviewers");

        var result = mvc.post().uri("/api/v1/admin/groups/" + group.getId() + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s"}
                        """.formatted(stranger.getId()))
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void addMemberRejectsMissingUserId() {
        var group = saveGroup("Reviewers");

        var result = mvc.post().uri("/api/v1/admin/groups/" + group.getId() + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void listMembersReturnsAll() {
        var group = saveGroup("Reviewers");
        saveMembership(group, reviewerUser, UserGroupMembershipSource.MANUAL);
        saveMembership(group, analyst, UserGroupMembershipSource.IDP);

        var result = mvc.get().uri("/api/v1/admin/groups/" + group.getId() + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.members[*].email").asArray()
                .containsExactlyInAnyOrder("rev@example.com", "analyst@example.com");
        assertThat(result).bodyJson().extractingPath("$.members[*].source").asArray()
                .containsExactlyInAnyOrder("MANUAL", "IDP");
    }

    @Test
    void removeMemberReturns204() {
        var group = saveGroup("Reviewers");
        saveMembership(group, reviewerUser, UserGroupMembershipSource.MANUAL);

        var result = mvc.delete()
                .uri("/api/v1/admin/groups/" + group.getId() + "/members/" + reviewerUser.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(membershipRepository.existsByUser_IdAndGroup_Id(reviewerUser.getId(),
                group.getId())).isFalse();
    }

    @Test
    void removeMemberWhenAbsentReturns404() {
        var group = saveGroup("Reviewers");

        var result = mvc.delete()
                .uri("/api/v1/admin/groups/" + group.getId() + "/members/" + reviewerUser.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("USER_GROUP_MEMBERSHIP_NOT_FOUND");
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName(name);
        org.setSlug(slug);
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, String displayName,
                                UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private UserGroupEntity saveGroup(String name) {
        return saveGroupForOrg(primaryOrg, name);
    }

    private UserGroupEntity saveGroupForOrg(OrganizationEntity org, String name) {
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return userGroupRepository.save(entity);
    }

    private void saveMembership(UserGroupEntity group, UserEntity user,
                                UserGroupMembershipSource source) {
        var entity = new UserGroupMembershipEntity();
        entity.setId(new UserGroupMembershipEntity.Id(user.getId(), group.getId()));
        entity.setUser(user);
        entity.setGroup(group);
        entity.setSource(source);
        entity.setJoinedAt(Instant.now());
        membershipRepository.save(entity);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt()
        );
        return jwtService.generateAccessToken(view);
    }
}
