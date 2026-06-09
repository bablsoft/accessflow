package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import java.security.interfaces.RSAPrivateCrtKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ConnectorControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private String adminToken;
    private String analystToken;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
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
        Path cacheDir = Files.createTempDirectory("connector-cache-");
        registry.add("accessflow.drivers.cache-dir", cacheDir::toString);
        // Offline keeps the test deterministic: only the bundled (postgresql) connector installs.
        registry.add("accessflow.drivers.offline", () -> "true");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        org = saveOrg();
        adminToken = generateToken(saveUser("admin@example.com", "Admin", UserRoleType.ADMIN));
        analystToken = generateToken(saveUser("analyst@example.com", "Analyst", UserRoleType.ANALYST));
    }

    @Test
    void listConnectorsReturnsCatalog() {
        var result = mvc.get().uri("/api/v1/datasources/connectors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.connectors[*].id").asArray()
                .contains("postgresql", "mysql", "clickhouse");
        assertThat(result).bodyJson().extractingPath("$.connectors[*].name").asArray()
                .contains("ClickHouse");
    }

    @Test
    void listConnectorsByAnalystReturns403() {
        var result = mvc.get().uri("/api/v1/datasources/connectors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void installBundledConnectorReturns200AndReady() {
        var result = mvc.post().uri("/api/v1/datasources/connectors/postgresql/install")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.id").asString().isEqualTo("postgresql");
        assertThat(result).bodyJson().extractingPath("$.driver_status").asString().isEqualTo("READY");
        assertThat(result).bodyJson().extractingPath("$.bundled").isEqualTo(true);
    }

    @Test
    void installUnknownConnectorReturns404() {
        var result = mvc.post().uri("/api/v1/datasources/connectors/does-not-exist/install")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("CONNECTOR_NOT_FOUND");
    }

    @Test
    void installOfflineExternalConnectorReturns422() {
        // mysql is non-bundled and the cache is empty in offline mode → driver unavailable.
        var result = mvc.post().uri("/api/v1/datasources/connectors/mysql/install")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_DRIVER_UNAVAILABLE");
    }

    @Test
    void installByAnalystReturns403() {
        var result = mvc.post().uri("/api/v1/datasources/connectors/postgresql/install")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    private OrganizationEntity saveOrg() {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Primary");
        entity.setSlug("primary");
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email, String displayName, UserRoleType role) {
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
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
