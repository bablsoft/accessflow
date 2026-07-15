package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class PlatformOrganizationControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OAuth2ConfigRepository oauth2ConfigRepository;
    @Autowired SamlConfigRepository samlConfigRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private String platformToken;     // ADMIN + platform_admin in primaryOrg
    private String adminToken;        // ADMIN, NOT platform_admin in primaryOrg
    private String otherOrgAdminToken; // ADMIN in otherOrg (for disabled-org test)

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

        datasourceRepository.deleteAll();
        oauth2ConfigRepository.deleteAll();
        samlConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary");
        otherOrg = saveOrg("Other", "other");

        var platformAdmin = saveUser(primaryOrg, "platform@example.com", UserRoleType.ADMIN, true);
        var admin = saveUser(primaryOrg, "admin@example.com", UserRoleType.ADMIN, false);
        var otherAdmin = saveUser(otherOrg, "otheradmin@example.com", UserRoleType.ADMIN, false);

        platformToken = generateToken(platformAdmin);
        adminToken = generateToken(admin);
        otherOrgAdminToken = generateToken(otherAdmin);
    }

    @Test
    void listReturnsAllOrgsForPlatformAdmin() {
        var result = mvc.get().uri("/api/v1/platform/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].slug").asArray()
                .containsExactlyInAnyOrder("primary", "other");
    }

    @Test
    void listForbiddenForNonPlatformAdmin() {
        var result = mvc.get().uri("/api/v1/platform/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void listUnauthorizedWithoutToken() {
        assertThat(mvc.get().uri("/api/v1/platform/organizations").exchange()).hasStatus(401);
    }

    @Test
    void createReturns201WithQuotasAndLocation() {
        var result = mvc.post().uri("/api/v1/platform/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Acme Corp","slug":"acme","max_datasources":5,
                         "max_users":20,"max_queries_per_day":1000}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("Acme Corp");
        assertThat(result).bodyJson().extractingPath("$.max_datasources").asNumber().isEqualTo(5);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/api/v1/platform/organizations/");
        assertThat(organizationRepository.findBySlug("acme")).isPresent();
    }

    @Test
    void createWithBlankNameReturns400() {
        var result = mvc.post().uri("/api/v1/platform/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"  ","max_datasources":-1}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void getReturns404ForUnknownOrg() {
        var result = mvc.get().uri("/api/v1/platform/organizations/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ORGANIZATION_NOT_FOUND");
    }

    @Test
    void updateChangesNameAndQuotas() {
        var result = mvc.put().uri("/api/v1/platform/organizations/" + otherOrg.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed","max_users":99}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("Renamed");
        assertThat(result).bodyJson().extractingPath("$.max_users").asNumber().isEqualTo(99);
    }

    @Test
    void disableThenEnableTogglesFlag() {
        var disable = mvc.post().uri("/api/v1/platform/organizations/" + otherOrg.getId() + "/disable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();
        assertThat(disable).hasStatus(204);
        assertThat(organizationRepository.findById(otherOrg.getId()).orElseThrow().isDisabled()).isTrue();

        var enable = mvc.post().uri("/api/v1/platform/organizations/" + otherOrg.getId() + "/enable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();
        assertThat(enable).hasStatus(204);
        assertThat(organizationRepository.findById(otherOrg.getId()).orElseThrow().isDisabled()).isFalse();
    }

    @Test
    void usageReturnsCountsAndLimits() {
        var result = mvc.get().uri("/api/v1/platform/organizations/" + primaryOrg.getId() + "/usage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.user_count").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.datasource_count").asNumber().isEqualTo(0);
    }

    @Test
    void disablingOrgBlocksItsUsersAtRequestTime() {
        // Sanity: the other-org admin can use an authenticated endpoint while enabled.
        assertThat(mvc.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherOrgAdminToken).exchange())
                .hasStatus(200);

        mvc.post().uri("/api/v1/platform/organizations/" + otherOrg.getId() + "/disable")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .exchange();

        // After disabling the org, the still-valid token is rejected at request time.
        assertThat(mvc.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherOrgAdminToken).exchange())
                .hasStatus(401);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName(name);
        org.setSlug(slug);
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, UserRoleType role,
                               boolean platformAdmin) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        user.setPlatformAdmin(platformAdmin);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), null, entity.roleName(), entity.getOrganization().getId(),
                entity.isActive(), entity.getAuthProvider(), entity.getPasswordHash(),
                entity.getLastLoginAt(), entity.getPreferredLanguage(), entity.isTotpEnabled(),
                entity.isPlatformAdmin(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
