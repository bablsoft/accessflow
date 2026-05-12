package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.EditionType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class CustomJdbcDriverControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CustomJdbcDriverRepository customJdbcDriverRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private String adminToken;
    private String analystToken;

    private static byte[] driverJarBytes;
    private static String driverJarSha256;

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

        Path cacheDir = Files.createTempDirectory("custom-driver-cache-");
        registry.add("accessflow.drivers.cache-dir", cacheDir::toString);

        driverJarBytes = Files.readAllBytes(Path.of(org.postgresql.Driver.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
        var digest = MessageDigest.getInstance("SHA-256");
        driverJarSha256 = HexFormat.of().formatHex(digest.digest(driverJarBytes));
    }

    @AfterEach
    void cleanup() {
        datasourceRepository.deleteAll();
        customJdbcDriverRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        datasourceRepository.deleteAll();
        customJdbcDriverRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary");
        admin = saveUser(primaryOrg, "admin@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst@example.com", "Analyst", UserRoleType.ANALYST);

        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @Test
    void uploadDriverReturns201AndPersistsRow() {
        var jar = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);

        var result = mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .multipart()
                .file(jar)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "org.postgresql.Driver")
                .param("expected_sha256", driverJarSha256)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.vendor_name").asString().isEqualTo("Acme");
        assertThat(result).bodyJson().extractingPath("$.target_db_type").asString()
                .isEqualTo("POSTGRESQL");
        assertThat(result).bodyJson().extractingPath("$.jar_sha256").asString()
                .isEqualToIgnoringCase(driverJarSha256);
        assertThat(customJdbcDriverRepository.countByOrganization_Id(primaryOrg.getId()))
                .isEqualTo(1);
    }

    @Test
    void uploadDriverWithWrongSha256Returns422() {
        var jar = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);

        var result = mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .multipart()
                .file(jar)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "org.postgresql.Driver")
                .param("expected_sha256", "0".repeat(64))
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("CUSTOM_DRIVER_CHECKSUM_MISMATCH");
        assertThat(customJdbcDriverRepository.countByOrganization_Id(primaryOrg.getId()))
                .isZero();
    }

    @Test
    void uploadDriverWithMissingClassInJarReturns422() {
        var jar = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);

        var result = mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .multipart()
                .file(jar)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "com.bogus.NotADriver")
                .param("expected_sha256", driverJarSha256)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("CUSTOM_DRIVER_INVALID_JAR");
    }

    @Test
    void uploadDriverByAnalystReturns403() {
        var jar = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);

        var result = mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .multipart()
                .file(jar)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "org.postgresql.Driver")
                .param("expected_sha256", driverJarSha256)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void uploadDuplicateDriverReturns409() {
        // First upload succeeds.
        var jar1 = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);
        mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .multipart()
                .file(jar1)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "org.postgresql.Driver")
                .param("expected_sha256", driverJarSha256)
                .exchange();

        // Second upload (same SHA) is rejected.
        var jar2 = new MockMultipartFile("jar", "ojdbc.jar", "application/java-archive",
                driverJarBytes);
        var result = mvc.post().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .multipart()
                .file(jar2)
                .param("vendor_name", "Acme")
                .param("target_db_type", "POSTGRESQL")
                .param("driver_class", "org.postgresql.Driver")
                .param("expected_sha256", driverJarSha256)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("CUSTOM_DRIVER_DUPLICATE");
    }

    @Test
    void listDriversReturnsOrgScopedRows() {
        var driver = saveCustomDriver();

        var result = mvc.get().uri("/api/v1/datasources/drivers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.drivers[*].id").asArray()
                .containsExactly(driver.getId().toString());
    }

    @Test
    void deleteDriverReturns204WhenNotReferenced() {
        var driver = saveCustomDriver();

        var result = mvc.delete().uri("/api/v1/datasources/drivers/" + driver.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(customJdbcDriverRepository.findById(driver.getId())).isEmpty();
    }

    @Test
    void deleteDriverReferencedByDatasourceReturns409WithReferencedBy() {
        var driver = saveCustomDriver();
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(primaryOrg);
        ds.setName("UsesDriver");
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("h");
        ds.setPort(5432);
        ds.setDatabaseName("d");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(1);
        ds.setMaxRowsPerQuery(100);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        ds.setCustomDriver(driver);
        datasourceRepository.save(ds);

        var result = mvc.delete().uri("/api/v1/datasources/drivers/" + driver.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("CUSTOM_DRIVER_IN_USE");
        assertThat(result).bodyJson().extractingPath("$.referencedBy").asArray()
                .containsExactly(ds.getId().toString());
    }

    private CustomJdbcDriverEntity saveCustomDriver() {
        // Use a fake but distinct SHA so no collision with the upload tests.
        var entity = new CustomJdbcDriverEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(primaryOrg);
        entity.setVendorName("Acme");
        entity.setTargetDbType(DbType.POSTGRESQL);
        entity.setDriverClass("org.postgresql.Driver");
        entity.setJarFilename("ojdbc.jar");
        entity.setJarSha256("c".repeat(64));
        entity.setJarSizeBytes(1024);
        entity.setStoragePath("custom/" + primaryOrg.getId() + "/" + entity.getId() + ".jar");
        entity.setUploadedBy(admin);
        return customJdbcDriverRepository.save(entity);
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
