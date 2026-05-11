package com.partqam.accessflow.api.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.ai.api.UpdateAiConfigCommand;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminSetupProgressControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired AiConfigRepository aiConfigRepository;
    @Autowired com.partqam.accessflow.ai.api.AiConfigService aiConfigService;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
    private String adminToken;
    private String analystToken;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kp.getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        aiConfigRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        aiConfigRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanRepository.deleteAll();
    }

    @Test
    void returnsZeroOfThreeOnFreshOrganization() {
        var result = mvc.get().uri("/api/v1/admin/setup-progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.datasources_configured").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.review_plans_configured").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.ai_provider_configured").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.completed_steps").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.total_steps").asNumber().isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.complete").asBoolean().isFalse();
    }

    @Test
    void reportsAllThreeOnceEachStepSatisfied() {
        seedDatasource();
        seedReviewPlan();
        aiConfigService.update(org.getId(), new UpdateAiConfigCommand(
                AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", null, "sk-test",
                null, null, null, null, null, null, null));

        var result = mvc.get().uri("/api/v1/admin/setup-progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.datasources_configured").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.review_plans_configured").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.ai_provider_configured").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.completed_steps").asNumber().isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.complete").asBoolean().isTrue();
    }

    @Test
    void forbidsNonAdminCallers() {
        var result = mvc.get().uri("/api/v1/admin/setup-progress")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void rejectsUnauthenticatedCallers() {
        var result = mvc.get().uri("/api/v1/admin/setup-progress").exchange();

        assertThat(result).hasStatus(401);
    }

    private void seedDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("primary-db");
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("db.local");
        ds.setPort(5432);
        ds.setDatabaseName("appdb");
        ds.setUsername("dbuser");
        ds.setPasswordEncrypted("ciphertext");
        ds.setActive(true);
        ds.setCreatedAt(Instant.now());
        datasourceRepository.save(ds);
    }

    private void seedReviewPlan() {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("default-plan");
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setAutoApproveReads(false);
        plan.setCreatedAt(Instant.now());
        reviewPlanRepository.save(plan);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(
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
