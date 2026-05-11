package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.security.internal.token.RefreshTokenStore;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminUserControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired RefreshTokenStore refreshTokenStore;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
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
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary");
        otherOrg = saveOrg("Other", "other");

        admin = saveUser(primaryOrg, "admin@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst@example.com", "Analyst", UserRoleType.ANALYST);
        saveUser(otherOrg, "stranger@example.com", "Stranger", UserRoleType.ANALYST);

        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @Test
    void listUsersReturnsOnlyCallerOrgUsers() {
        var result = mvc.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].email").asArray()
                .containsExactlyInAnyOrder("admin@example.com", "analyst@example.com");
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(2);
    }

    @Test
    void listUsersWithoutAdminRoleReturns403() {
        var result = mvc.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void listUsersWithoutTokenReturns401() {
        var result = mvc.get().uri("/api/v1/admin/users").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void createUserReturns201WithoutPasswordHash() throws Exception {
        var result = mvc.post().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"new@example.com","password":"Password123!",
                         "display_name":"New User","role":"REVIEWER"}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.email").asString().isEqualTo("new@example.com");
        assertThat(result).bodyJson().extractingPath("$.role").asString().isEqualTo("REVIEWER");
        assertThat(result).bodyJson().extractingPath("$.auth_provider").asString().isEqualTo("LOCAL");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("passwordHash");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).contains("/api/v1/admin/users/");

        var saved = userRepository.findByEmail("new@example.com").orElseThrow();
        assertThat(passwordEncoder.matches("Password123!", saved.getPasswordHash())).isTrue();
    }

    @Test
    void createUserWithDuplicateEmailReturns409() {
        var result = mvc.post().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"analyst@example.com","password":"Password123!",
                         "display_name":"Dup","role":"ANALYST"}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void createUserWithInvalidBodyReturns400() {
        var result = mvc.post().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"not-an-email","password":"short","role":"ANALYST"}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void updateUserAppliesRoleAndActiveChange() {
        var result = mvc.put().uri("/api/v1/admin/users/" + analyst.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role":"REVIEWER","active":false}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.role").asString().isEqualTo("REVIEWER");
        assertThat(result).bodyJson().extractingPath("$.active").asBoolean().isFalse();
    }

    @Test
    void updateUserBlocksSelfDemotionFromAdmin() {
        var result = mvc.put().uri("/api/v1/admin/users/" + admin.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role":"ANALYST"}
                        """)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_USER_OPERATION");
    }

    @Test
    void updateUserNotInOrgReturns404() {
        var stranger = userRepository.findByEmail("stranger@example.com").orElseThrow();
        var result = mvc.put().uri("/api/v1/admin/users/" + stranger.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role":"REVIEWER"}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void deactivateUserReturns204AndRevokesRefreshTokens() {
        refreshTokenStore.store("rt-analyst", analyst.getId().toString(), 3600);

        var result = mvc.delete().uri("/api/v1/admin/users/" + analyst.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        var reloaded = userRepository.findById(analyst.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(refreshTokenStore.isRevoked("rt-analyst")).isTrue();
    }

    @Test
    void deactivateSelfReturns422() {
        var result = mvc.delete().uri("/api/v1/admin/users/" + admin.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(422);
    }

    @Test
    void deactivateUserFromOtherOrgReturns404() {
        var stranger = userRepository.findByEmail("stranger@example.com").orElseThrow();
        var result = mvc.delete().uri("/api/v1/admin/users/" + stranger.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName(name);
        org.setSlug(slug);
        org.setEdition(EditionType.COMMUNITY);
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

    private String generateToken(UserEntity entity) {
        var view = new com.partqam.accessflow.core.api.UserView(
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
