package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DataClassification;
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
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DiscoveryControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DiscoveryScanConfigRepository configRepository;
    @Autowired DiscoveryFindingRepository findingRepository;
    @Autowired DataClassificationTagRepository tagRepository;
    @Autowired MaskingPolicyRepository maskingPolicyRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private DatasourceEntity datasource;
    private String adminToken;
    private String analystToken;

    @AfterEach
    void cleanup() {
        findingRepository.deleteAll();
        configRepository.deleteAll();
        tagRepository.deleteAll();
        maskingPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        findingRepository.deleteAll();
        configRepository.deleteAll();
        tagRepository.deleteAll();
        maskingPolicyRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary-disc");
        var admin = saveUser(primaryOrg, "admin-disc@example.com", "Admin", UserRoleType.ADMIN);
        var analyst = saveUser(primaryOrg, "analyst-disc@example.com", "Analyst",
                UserRoleType.ANALYST);
        datasource = saveDatasource(primaryOrg, "Discovery-DS");
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    private String base() {
        return "/api/v1/datasources/" + datasource.getId() + "/discovery";
    }

    @Test
    void getConfigSynthesizesDefaultsWhenUnconfigured() {
        var result = mvc.get().uri(base() + "/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.sample_size").asNumber().isEqualTo(100);
        assertThat(result).bodyJson().extractingPath("$.scan_interval_hours").asNumber()
                .isEqualTo(24);
    }

    @Test
    void putConfigUpsertsAndPersists() {
        var result = mvc.put().uri(base() + "/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enabled":true,"sample_size":250,"scan_interval_hours":12,
                         "ai_classification_enabled":true}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.sample_size").asNumber().isEqualTo(250);

        var persisted = configRepository.findByDatasourceId(datasource.getId()).orElseThrow();
        assertThat(persisted.isEnabled()).isTrue();
        assertThat(persisted.getSampleSize()).isEqualTo(250);
        assertThat(persisted.getScanIntervalHours()).isEqualTo(12);
        assertThat(persisted.isAiClassificationEnabled()).isTrue();
    }

    @Test
    void putConfigWithOutOfRangeSampleSizeReturns400() {
        var result = mvc.put().uri(base() + "/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sample_size\":5}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void endpointsRequireDataClassificationManageAuthority() {
        var result = mvc.get().uri(base() + "/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void unknownDatasourceReturns404() {
        var result = mvc.get()
                .uri("/api/v1/datasources/" + UUID.randomUUID() + "/discovery/config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void findingsListFiltersByStatus() {
        saveFinding("email", DataClassification.PII, DiscoveryFindingStatus.PENDING);
        saveFinding("iban", DataClassification.FINANCIAL, DiscoveryFindingStatus.DISMISSED);

        var result = mvc.get().uri(base() + "/findings?status=PENDING")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].column_name").asString()
                .isEqualTo("email");
        assertThat(result).bodyJson().extractingPath("$.content[0].detector").asString()
                .isEqualTo("EMAIL");
    }

    @Test
    void bulkConfirmCreatesTagDerivesMaskingAndMarksConfirmed() {
        var finding = saveFinding("email", DataClassification.PII,
                DiscoveryFindingStatus.PENDING);
        var unknownId = UUID.randomUUID();

        var result = mvc.post().uri(base() + "/findings/bulk-decision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"finding_ids\":[\"" + finding.getId() + "\",\"" + unknownId
                        + "\"],\"decision\":\"CONFIRM\"}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.results[0].status").asString()
                .isEqualTo("SUCCESS");
        assertThat(result).bodyJson().extractingPath("$.results[0].new_status").asString()
                .isEqualTo("CONFIRMED");
        assertThat(result).bodyJson().extractingPath("$.results[1].status").asString()
                .isEqualTo("NOT_FOUND");

        // The AF-447 tag exists and derived a masking policy for the column.
        var tags = tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        primaryOrg.getId(), datasource.getId());
        assertThat(tags).hasSize(1);
        assertThat(tags.getFirst().getTableName()).isEqualTo("public.users");
        assertThat(tags.getFirst().getColumnName()).isEqualTo("email");
        var policies = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(primaryOrg.getId(),
                        datasource.getId());
        assertThat(policies).hasSize(1);
        assertThat(policies.getFirst().getColumnRef()).isEqualTo("public.users.email");

        var persisted = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DiscoveryFindingStatus.CONFIRMED);
        assertThat(persisted.getDecidedBy()).isNotNull();
    }

    @Test
    void bulkDismissSuppressesFinding() {
        var finding = saveFinding("phone", DataClassification.PII,
                DiscoveryFindingStatus.PENDING);

        var result = mvc.post().uri(base() + "/findings/bulk-decision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"finding_ids\":[\"" + finding.getId()
                        + "\"],\"decision\":\"DISMISS\"}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.results[0].new_status").asString()
                .isEqualTo("DISMISSED");
        assertThat(findingRepository.findById(finding.getId()).orElseThrow().getStatus())
                .isEqualTo(DiscoveryFindingStatus.DISMISSED);
        assertThat(tagRepository.count()).isZero();
    }

    @Test
    void bulkDecisionWithEmptyIdsReturns400() {
        var result = mvc.post().uri(base() + "/findings/bulk-decision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"finding_ids\":[],\"decision\":\"CONFIRM\"}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void triggerScanReturns202() {
        var result = mvc.post().uri(base() + "/scan")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        // The async scan itself fails against the unreachable test datasource — by design the
        // trigger only validates ownership and accepts.
        assertThat(result).hasStatus(202);
    }

    private DiscoveryFindingEntity saveFinding(String column, DataClassification classification,
                                               DiscoveryFindingStatus status) {
        var finding = new DiscoveryFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(primaryOrg.getId());
        finding.setDatasourceId(datasource.getId());
        finding.setSchemaName("public");
        finding.setTableName("users");
        finding.setColumnName(column);
        finding.setClassification(classification);
        finding.setDetector(column.equals("email") ? DiscoveryDetector.EMAIL
                : DiscoveryDetector.PHONE);
        finding.setConfidence(90);
        finding.setSampleRedacted("****");
        finding.setMatchCount(9);
        finding.setSampleCount(10);
        finding.setStatus(status);
        finding.setFirstDetectedAt(Instant.now());
        finding.setLastDetectedAt(Instant.now());
        return findingRepository.save(finding);
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
