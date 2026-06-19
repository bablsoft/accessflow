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
import com.bablsoft.accessflow.core.internal.persistence.repo.DataClassificationTagRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
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
class DataClassificationTagControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DataClassificationTagRepository tagRepository;
    @Autowired MaskingPolicyRepository maskingPolicyRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private UserEntity admin;
    private UserEntity analyst;
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
        tagRepository.deleteAll();
        maskingPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        tagRepository.deleteAll();
        maskingPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary-dct");
        admin = saveUser(primaryOrg, "admin-dct@example.com", "Admin", UserRoleType.ADMIN);
        analyst = saveUser(primaryOrg, "analyst-dct@example.com", "Analyst", UserRoleType.ANALYST);
        datasource = saveDatasource(primaryOrg, "Tagged-DS");
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/classification-tags";
    }

    @Test
    void createReturns201PersistsTagAndDerivesMaskingPolicy() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","column_name":"email",
                         "classifications":["PII"],"note":"contact info"}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).contains("/classification-tags");
        assertThat(result).bodyJson().extractingPath("$.content[0].classification").asString()
                .isEqualTo("PII");
        assertThat(tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        primaryOrg.getId(), datasource.getId())).hasSize(1);
        // PII column-level tag derived a masking policy.
        var policies = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                        primaryOrg.getId(), datasource.getId());
        assertThat(policies).hasSize(1);
        assertThat(policies.getFirst().getColumnRef()).isEqualTo("users.email");
    }

    @Test
    void createWithMultipleClassificationsReturnsAllTags() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","column_name":"email",
                         "classifications":["PII","GDPR"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.content[*].classification").asArray()
                .containsExactlyInAnyOrder("PII", "GDPR");
    }

    @Test
    void listReturnsTags() {
        createTag("orders", null, "PCI");

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].table_name").asArray()
                .containsExactly("orders");
    }

    @Test
    void deleteReturns204AndKeepsDerivedMaskingPolicy() {
        createTag("users", "email", "PII");
        var tagId = tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        primaryOrg.getId(), datasource.getId()).getFirst().getId();

        var result = mvc.delete().uri(base() + "/" + tagId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(tagRepository.findById(tagId)).isEmpty();
        // Non-cascade: the derived masking policy survives the tag deletion.
        assertThat(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
                        primaryOrg.getId(), datasource.getId())).hasSize(1);
    }

    @Test
    void derivationPreviewAggregatesPosture() {
        createTag("users", "email", "PII");
        createTag("orders", null, "PCI");

        var result = mvc.get().uri(base() + "/derivation-preview")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson()
                .extractingPath("$.suggested_review_posture.min_approvals").asNumber().isEqualTo(2);
        assertThat(result).bodyJson()
                .extractingPath("$.masking_suggestions[*].column_ref").asArray()
                .containsExactly("users.email");
    }

    @Test
    void createByAnalystReturns403() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","classifications":["PII"]}
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
                        {"table_name":"","classifications":["PII"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void createWithEmptyClassificationsReturns400() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","classifications":[]}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void createDuplicateReturns422() {
        createTag("users", "email", "PII");

        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","column_name":"email","classifications":["PII"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_DATA_CLASSIFICATION_TAG");
    }

    @Test
    void createForUnknownDatasourceReturns404() {
        var result = mvc.post()
                .uri("/api/v1/datasources/" + UUID.randomUUID() + "/classification-tags")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"table_name":"users","classifications":["PII"]}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    private void createTag(String table, String column, String classification) {
        var columnJson = column == null ? "" : "\"column_name\":\"" + column + "\",";
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"table_name\":\"" + table + "\"," + columnJson
                        + "\"classifications\":[\"" + classification + "\"]}")
                .exchange();
        assertThat(result).hasStatus(201);
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
