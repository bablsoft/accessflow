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
import org.junit.jupiter.api.AfterEach;
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
class DatasourceControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity stranger;
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
        var cacheDir = com.partqam.accessflow.proxy.internal.driver
                .DriverCacheTestSupport.prepareCacheWithMysql();
        registry.add("accessflow.drivers.cache-dir", cacheDir::toString);
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

        primaryOrg = saveOrg("Primary", "primary");
        otherOrg = saveOrg("Other", "other");

        admin = saveUser(primaryOrg, "admin@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst@example.com", "Analyst", UserRoleType.ANALYST);
        stranger = saveUser(otherOrg, "stranger@example.com", "Stranger", UserRoleType.ADMIN);

        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @Test
    void createDatasourceReturns201AndPersistsEncryptedPassword() throws Exception {
        var result = mvc.post().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Production",
                          "db_type": "POSTGRESQL",
                          "host": "db.example.com",
                          "port": 5432,
                          "database_name": "appdb",
                          "username": "svc",
                          "password": "super-secret-pw",
                          "ssl_mode": "REQUIRE",
                          "connection_pool_size": 12,
                          "max_rows_per_query": 500,
                          "require_review_reads": false,
                          "require_review_writes": true,
                          "ai_analysis_enabled": true
                        }
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/api/v1/datasources/");
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("Production");
        assertThat(result).bodyJson().extractingPath("$.db_type").asString()
                .isEqualTo("POSTGRESQL");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("super-secret-pw");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");

        var saved = datasourceRepository.findAllByOrganization_Id(primaryOrg.getId()).get(0);
        assertThat(saved.getPasswordEncrypted()).isNotEqualTo("super-secret-pw");
        assertThat(encryptionService.decrypt(saved.getPasswordEncrypted()))
                .isEqualTo("super-secret-pw");
    }

    @Test
    void createDatasourceWithDuplicateNameReturns409() {
        saveDatasource(primaryOrg, "Production");

        var result = mvc.post().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Production","db_type":"POSTGRESQL","host":"h","port":5432,
                         "database_name":"d","username":"u","password":"p","ssl_mode":"DISABLE"}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_NAME_ALREADY_EXISTS");
    }

    @Test
    void createDatasourceWithInvalidBodyReturns400() {
        var result = mvc.post().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","db_type":"POSTGRESQL","host":"h","port":99999,
                         "database_name":"d","username":"u","password":"p","ssl_mode":"DISABLE"}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createDatasourceByAnalystReturns403() {
        var result = mvc.post().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"X","db_type":"POSTGRESQL","host":"h","port":5432,
                         "database_name":"d","username":"u","password":"p","ssl_mode":"DISABLE"}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void createDatasourceWithoutTokenReturns401() {
        var result = mvc.post().uri("/api/v1/datasources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void listTypesReturnsCatalogForAllSupportedDbTypes() {
        var result = mvc.get().uri("/api/v1/datasources/types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.types[*].code").asArray()
                .containsExactlyInAnyOrder("POSTGRESQL", "MYSQL", "MARIADB", "ORACLE", "MSSQL");
        assertThat(result).bodyJson()
                .extractingPath("$.types[?(@.code=='POSTGRESQL')].driver_status").asArray()
                .containsExactly("READY");
        assertThat(result).bodyJson()
                .extractingPath("$.types[?(@.code=='POSTGRESQL')].default_port").asArray()
                .containsExactly(5432);
        assertThat(result).bodyJson()
                .extractingPath("$.types[?(@.code=='POSTGRESQL')].jdbc_url_template").asArray()
                .containsExactly("jdbc:postgresql://{host}:{port}/{database_name}");
    }

    @Test
    void listDatasourcesAsAdminReturnsAllInOrg() {
        saveDatasource(primaryOrg, "DS-A");
        saveDatasource(primaryOrg, "DS-B");
        saveDatasource(otherOrg, "DS-other");

        var result = mvc.get().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].name").asArray()
                .containsExactlyInAnyOrder("DS-A", "DS-B");
    }

    @Test
    void listDatasourcesAsAnalystReturnsOnlyPermittedOnes() {
        var visible = saveDatasource(primaryOrg, "Visible");
        saveDatasource(primaryOrg, "Hidden");
        savePermission(visible, analyst, admin, true, false, false);

        var result = mvc.get().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].name").asArray()
                .containsExactly("Visible");
    }

    @Test
    void getDatasourceByIdAsAdminReturnsView() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.id").asString()
                .isEqualTo(ds.getId().toString());
    }

    @Test
    void getDatasourceByIdAsAnalystWithoutPermissionReturns404() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void getDatasourceFromOtherOrgReturns404() {
        var ds = saveDatasource(otherOrg, "OtherDS");

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void updateDatasourceAppliesPartialFields() {
        var ds = saveDatasource(primaryOrg, "DS");
        var oldEncrypted = ds.getPasswordEncrypted();

        var result = mvc.put().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"host":"new-host","connection_pool_size":42}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.host").asString().isEqualTo("new-host");
        assertThat(result).bodyJson().extractingPath("$.connection_pool_size").asNumber()
                .isEqualTo(42);
        var reloaded = datasourceRepository.findById(ds.getId()).orElseThrow();
        assertThat(reloaded.getPasswordEncrypted()).isEqualTo(oldEncrypted);
    }

    @Test
    void updateDatasourcePasswordReencryptsIt() {
        var ds = saveDatasource(primaryOrg, "DS");
        var oldEncrypted = ds.getPasswordEncrypted();

        var result = mvc.put().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password":"rotated-secret"}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        var reloaded = datasourceRepository.findById(ds.getId()).orElseThrow();
        assertThat(reloaded.getPasswordEncrypted()).isNotEqualTo(oldEncrypted);
        assertThat(encryptionService.decrypt(reloaded.getPasswordEncrypted()))
                .isEqualTo("rotated-secret");
    }

    @Test
    void updateDatasourceRenameToConflictReturns409() {
        saveDatasource(primaryOrg, "Existing");
        var ds = saveDatasource(primaryOrg, "Other");

        var result = mvc.put().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Existing"}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_NAME_ALREADY_EXISTS");
    }

    @Test
    void deleteDatasourceSoftDeactivates() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.delete().uri("/api/v1/datasources/" + ds.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        var reloaded = datasourceRepository.findById(ds.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
    }

    @Test
    void grantPermissionCreates201() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","can_read":true,"can_write":true,
                         "allowed_schemas":["public"]}
                        """.formatted(analyst.getId()))
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.user_id").asString()
                .isEqualTo(analyst.getId().toString());
        assertThat(result).bodyJson().extractingPath("$.can_read").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.allowed_schemas").asArray()
                .containsExactly("public");
    }

    @Test
    void grantPermissionPersistsRestrictedColumns() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","can_read":true,
                         "restricted_columns":["public.users.ssn","public.users.email"]}
                        """.formatted(analyst.getId()))
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.restricted_columns").asArray()
                .containsExactly("public.users.ssn", "public.users.email");
    }

    @Test
    void grantPermissionRejectsBlankRestrictedColumn() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","can_read":true,
                         "restricted_columns":["public.users.ssn", "  "]}
                        """.formatted(analyst.getId()))
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void grantDuplicatePermissionReturns409() {
        var ds = saveDatasource(primaryOrg, "DS");
        savePermission(ds, analyst, admin, true, false, false);

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","can_read":true}
                        """.formatted(analyst.getId()))
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_PERMISSION_ALREADY_EXISTS");
    }

    @Test
    void grantPermissionForUserInOtherOrgReturns422() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","can_read":true}
                        """.formatted(stranger.getId()))
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_DATASOURCE_PERMISSION");
    }

    @Test
    void listPermissionsReturnsAllForDatasource() {
        var ds = saveDatasource(primaryOrg, "DS");
        savePermission(ds, analyst, admin, true, false, false);

        var result = mvc.get().uri("/api/v1/datasources/" + ds.getId() + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].user_email").asArray()
                .contains("analyst@example.com");
    }

    @Test
    void revokePermissionReturns204() {
        var ds = saveDatasource(primaryOrg, "DS");
        var perm = savePermission(ds, analyst, admin, true, false, false);

        var result = mvc.delete()
                .uri("/api/v1/datasources/" + ds.getId() + "/permissions/" + perm.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(permissionRepository.findById(perm.getId())).isEmpty();
    }

    @Test
    void revokeUnknownPermissionReturns404() {
        var ds = saveDatasource(primaryOrg, "DS");
        var randomId = UUID.randomUUID();

        var result = mvc.delete()
                .uri("/api/v1/datasources/" + ds.getId() + "/permissions/" + randomId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void testConnectionFailsForUnreachableHost() {
        var ds = saveDatasource(primaryOrg, "DS");

        var result = mvc.post().uri("/api/v1/datasources/" + ds.getId() + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_CONNECTION_TEST_FAILED");
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

    private DatasourceEntity saveDatasource(OrganizationEntity org, String name) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private DatasourceUserPermissionEntity savePermission(DatasourceEntity ds, UserEntity user,
                                                          UserEntity grantedBy, boolean canRead,
                                                          boolean canWrite, boolean canDdl) {
        var perm = new DatasourceUserPermissionEntity();
        perm.setId(UUID.randomUUID());
        perm.setDatasource(ds);
        perm.setUser(user);
        perm.setCreatedBy(grantedBy);
        perm.setCanRead(canRead);
        perm.setCanWrite(canWrite);
        perm.setCanDdl(canDdl);
        return permissionRepository.save(perm);
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
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
