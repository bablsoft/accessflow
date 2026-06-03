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
import com.bablsoft.accessflow.core.internal.persistence.repo.RowSecurityPolicyRepository;
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
class RowSecurityPolicyControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired RowSecurityPolicyRepository rowSecurityPolicyRepository;
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
    }

    @AfterEach
    void cleanup() {
        rowSecurityPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        rowSecurityPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary-rls");
        otherOrg = saveOrg("Other", "other-rls");
        admin = saveUser(primaryOrg, "admin-rls@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst-rls@example.com", "Analyst", UserRoleType.ANALYST);
        stranger = saveUser(otherOrg, "stranger-rls@example.com", "Stranger", UserRoleType.ANALYST);
        datasource = saveDatasource(primaryOrg, "RLS-DS");
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/row-security-policies";
    }

    @Test
    void createReturns201AndPersistsPolicy() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"public.orders","column_name":"region",
                         "operator":"EQUALS","value_type":"VARIABLE","value_expression":":user.region",
                         "applies_to_roles":["ANALYST"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/row-security-policies/");
        assertThat(result).bodyJson().extractingPath("$.table_name").asString()
                .isEqualTo("public.orders");
        assertThat(result).bodyJson().extractingPath("$.value_expression").asString()
                .isEqualTo("user.region");
        assertThat(result).bodyJson().extractingPath("$.applies_to_roles").asArray()
                .containsExactly("ANALYST");
        assertThat(rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                        primaryOrg.getId(), datasource.getId())).hasSize(1);
    }

    @Test
    void listReturnsPolicies() {
        createPolicy("orders", "region");

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].table_name").asArray()
                .containsExactly("orders");
    }

    @Test
    void updateReturns200() {
        var id = createPolicy("orders", "region");

        var result = mvc.put().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","column_name":"tenant","operator":"NOT_EQUALS",
                         "value_type":"LITERAL","value_expression":"blocked","enabled":false}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.operator").asString().isEqualTo("NOT_EQUALS");
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
    }

    @Test
    void deleteReturns204() {
        var id = createPolicy("orders", "region");

        var result = mvc.delete().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(rowSecurityPolicyRepository.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void createByAnalystReturns403() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","column_name":"region","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU"}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void createWithBlankTableReturns400() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"","column_name":"region","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU"}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void createWithVariableOutsideUserNamespaceReturns422() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","column_name":"region","operator":"EQUALS",
                         "value_type":"VARIABLE","value_expression":"sys.region"}
                        """)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_ROW_SECURITY_POLICY");
    }

    @Test
    void createForUnknownDatasourceReturns404() {
        var result = mvc.post()
                .uri("/api/v1/datasources/" + UUID.randomUUID() + "/row-security-policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","column_name":"region","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU"}
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
                        {"table_name":"orders","column_name":"region","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU"}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void createWithAppliesUserInOtherOrgReturns422() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"orders","column_name":"region","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU",
                         "applies_to_user_ids":["%s"]}
                        """.formatted(stranger.getId()))
                .exchange();

        assertThat(result).hasStatus(422);
    }

    private String createPolicy(String table, String column) {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"%s","column_name":"%s","operator":"EQUALS",
                         "value_type":"LITERAL","value_expression":"EU"}
                        """.formatted(table, column))
                .exchange();
        assertThat(result).hasStatus(201);
        return rowSecurityPolicyRepository
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
