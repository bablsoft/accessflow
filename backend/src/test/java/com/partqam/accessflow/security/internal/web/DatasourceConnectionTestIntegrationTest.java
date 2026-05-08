package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DatasourceConnectionTestIntegrationTest {

    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer customerDb = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("customer")
            .withUsername("customer_user")
            .withPassword("customer-pw");

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
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
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");
    }

    @BeforeAll
    static void startCustomerDb() throws Exception {
        customerDb.start();
        try (var connection = DriverManager.getConnection(customerDb.getJdbcUrl(),
                customerDb.getUsername(), customerDb.getPassword());
             var stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE customers (id UUID PRIMARY KEY, email VARCHAR(255) NOT NULL)");
            stmt.execute("CREATE TABLE orders (id UUID PRIMARY KEY, total INTEGER, "
                    + "customer_id UUID NOT NULL)");
        }
    }

    @AfterAll
    static void stopCustomerDb() {
        customerDb.stop();
    }

    @AfterEach
    void cleanup() {
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @Test
    void testConnectionAgainstRunningPostgresReturnsOk() {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(),
                customerDb.getDatabaseName());

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.ok").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.latency_ms").asNumber().isNotNull();
    }

    @Test
    void testConnectionWithBadPasswordReturns422() {
        var ds = saveDatasource(customerDb.getUsername(), "wrong-password",
                customerDb.getDatabaseName());

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_CONNECTION_TEST_FAILED");
    }

    @Test
    void getSchemaAsAdminListsTables() throws Exception {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(),
                customerDb.getDatabaseName());

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId() + "/schema")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("customers");
        assertThat(body).contains("orders");
        assertThat(body).doesNotContain("pg_catalog");
        assertThat(body).doesNotContain("information_schema");
    }

    @Test
    void getSchemaAsAnalystWithoutPermissionReturns404() {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(),
                customerDb.getDatabaseName());

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId() + "/schema")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void getSchemaAsAnalystWithPermissionReturnsTables() throws Exception {
        var ds = saveDatasource(customerDb.getUsername(), customerDb.getPassword(),
                customerDb.getDatabaseName());
        var perm = new DatasourceUserPermissionEntity();
        perm.setId(UUID.randomUUID());
        perm.setDatasource(ds);
        perm.setUser(analyst);
        perm.setCreatedBy(admin);
        perm.setCanRead(true);
        permissionRepository.save(perm);

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId() + "/schema")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentAsString()).contains("customers");
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource(String username, String password, String dbName) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Customer-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(5432));
        ds.setDatabaseName(dbName);
        ds.setUsername(username);
        ds.setPasswordEncrypted(encryptionService.encrypt(password));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
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
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
