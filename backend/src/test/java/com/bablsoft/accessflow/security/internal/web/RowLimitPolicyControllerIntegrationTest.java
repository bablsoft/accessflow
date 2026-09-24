package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowLimitPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RowLimitPolicyControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired RowLimitPolicyRepository rowLimitPolicyRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity stranger;
    private DatasourceEntity datasource;
    private String adminToken;
    private String analystToken;

    @AfterEach
    void cleanup() {
        rowLimitPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        rowLimitPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary-rlp");
        otherOrg = saveOrg("Other", "other-rlp");
        admin = saveUser(primaryOrg, "admin-rlp@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst-rlp@example.com", "Analyst", UserRoleType.ANALYST);
        stranger = saveUser(otherOrg, "stranger-rlp@example.com", "Stranger", UserRoleType.ANALYST);
        datasource = saveDatasource(primaryOrg, "RLP-DS");
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/row-limit-policies";
    }

    @Test
    void createReturns201AndPersistsNormalizedPolicy() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"schema_name":"CRM","table_name":"Customer","max_rows":250,
                         "applies_to_roles":["ANALYST"],"applies_to_user_ids":["%s"],
                         "enabled":true}
                        """.formatted(analyst.getId()))
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/row-limit-policies/");
        assertThat(result).bodyJson().extractingPath("$.schema_name").asString().isEqualTo("crm");
        assertThat(result).bodyJson().extractingPath("$.table_name").asString()
                .isEqualTo("customer");
        assertThat(result).bodyJson().extractingPath("$.max_rows").asNumber().isEqualTo(250);
        assertThat(result).bodyJson().extractingPath("$.applies_to_roles").asArray()
                .containsExactly("ANALYST");
        assertThat(rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                        primaryOrg.getId(), datasource.getId())).hasSize(1);
    }

    @Test
    void listReturnsPolicies() {
        createPolicy("orders", 10);

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].table_name").asArray()
                .containsExactly("orders");
    }

    @Test
    void updateReturns200() {
        var id = createPolicy("orders", 10);

        var result = mvc.put().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":75,"enabled":false}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.max_rows").asNumber().isEqualTo(75);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
    }

    @Test
    void deleteReturns204() {
        var id = createPolicy("orders", 10);

        var result = mvc.delete().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(rowLimitPolicyRepository.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void createByAnalystReturns403() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":10}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void createWithBlankTableOrMissingMaxRowsReturns400() {
        var blankTable = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"","max_rows":10}
                        """)
                .exchange();
        var zeroRows = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":0}
                        """)
                .exchange();

        assertThat(blankTable).hasStatus(400);
        assertThat(zeroRows).hasStatus(400);
    }

    @Test
    void createWithUnknownRoleReturns422() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":10,"applies_to_roles":["GHOST"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_ROW_LIMIT_POLICY");
    }

    @Test
    void createWithAppliesUserInOtherOrgReturns422() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":10,"applies_to_user_ids":["%s"]}
                        """.formatted(stranger.getId()))
                .exchange();

        assertThat(result).hasStatus(422);
    }

    @Test
    void createForUnknownDatasourceReturns404() {
        var result = mvc.post()
                .uri("/api/v1/datasources/" + UUID.randomUUID() + "/row-limit-policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":10}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void updateUnknownPolicyReturns404() {
        var result = mvc.put().uri(base() + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","max_rows":10}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ROW_LIMIT_POLICY_NOT_FOUND");
    }

    private String createPolicy(String table, int maxRows) {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"%s","max_rows":%d}
                        """.formatted(table, maxRows))
                .exchange();
        assertThat(result).hasStatus(201);
        return rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                        primaryOrg.getId(), datasource.getId())
                .getLast().getId().toString();
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
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
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
