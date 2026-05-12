package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
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
class ReviewPlanControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository approverRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;
    private OrganizationEntity primaryOrg;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity reviewer;
    private UserEntity analyst;
    private String adminToken;
    private String reviewerToken;
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
        datasourceRepository.deleteAll();
        approverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        datasourceRepository.deleteAll();
        approverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        primaryOrg = saveOrg("Primary", "primary");
        otherOrg = saveOrg("Other", "other");

        admin = saveUser(primaryOrg, "admin@example.com", "Admin", UserRoleType.ADMIN);
        reviewer = saveUser(primaryOrg, "reviewer@example.com", "Reviewer", UserRoleType.REVIEWER);
        analyst = saveUser(primaryOrg, "analyst@example.com", "Analyst", UserRoleType.ANALYST);

        adminToken = generateToken(admin);
        reviewerToken = generateToken(reviewer);
        analystToken = generateToken(analyst);
    }

    @Test
    void createReturns201AndPersistsPlanWithApprovers() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "PII writes",
                          "description": "All writes to PII tables",
                          "requires_ai_review": true,
                          "requires_human_approval": true,
                          "min_approvals_required": 1,
                          "approval_timeout_hours": 24,
                          "auto_approve_reads": false,
                          "approvers": [
                            {"role": "REVIEWER", "stage": 1}
                          ]
                        }
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/api/v1/review-plans/");
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("PII writes");
        assertThat(result).bodyJson().extractingPath("$.requires_human_approval").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.approvers[0].role").asString()
                .isEqualTo("REVIEWER");

        assertThat(reviewPlanRepository.findAllByOrganization_Id(primaryOrg.getId())).hasSize(1);
    }

    @Test
    void createWithBlankNameReturns400() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","approvers":[{"role":"REVIEWER","stage":1}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createWithoutApproversWhenHumanApprovalRequiredReturns422() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Plan","requires_human_approval":true,"approvers":[]}
                        """)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_REVIEW_PLAN");
    }

    @Test
    void createWithDuplicateNameReturns409() {
        savePlan(primaryOrg, "Existing");

        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Existing","approvers":[{"role":"REVIEWER","stage":1}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REVIEW_PLAN_NAME_ALREADY_EXISTS");
    }

    @Test
    void createByAnalystReturns403() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Plan","approvers":[{"role":"REVIEWER","stage":1}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void createByReviewerReturns403() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Plan","approvers":[{"role":"REVIEWER","stage":1}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void createWithoutTokenReturns401() {
        var result = mvc.post().uri("/api/v1/review-plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void listReturnsPlansForCallerOrgOnly() {
        savePlan(primaryOrg, "Mine-A");
        savePlan(primaryOrg, "Mine-B");
        savePlan(otherOrg, "Other-Plan");

        var result = mvc.get().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[*].name").asArray()
                .containsExactly("Mine-A", "Mine-B");
    }

    @Test
    void listIsAccessibleToReviewerAndAnalyst() {
        savePlan(primaryOrg, "Plan");

        assertThat(mvc.get().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .exchange()).hasStatus(200);
        assertThat(mvc.get().uri("/api/v1/review-plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(200);
    }

    @Test
    void getByIdReturnsPlan() {
        var plan = savePlan(primaryOrg, "Plan");

        var result = mvc.get().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.id").asString()
                .isEqualTo(plan.getId().toString());
        assertThat(result).bodyJson().extractingPath("$.organization_id").asString()
                .isEqualTo(primaryOrg.getId().toString());
    }

    @Test
    void getPlanFromOtherOrgReturns404() {
        var plan = savePlan(otherOrg, "OtherPlan");

        var result = mvc.get().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REVIEW_PLAN_NOT_FOUND");
    }

    @Test
    void updateAppliesPartialFieldsAndReplacesApprovers() {
        var plan = savePlan(primaryOrg, "Plan");
        saveApprover(plan, null, UserRoleType.REVIEWER, 1);

        var result = mvc.put().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description":"updated","approval_timeout_hours":48,
                         "approvers":[{"role":"ADMIN","stage":1}]}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.description").asString().isEqualTo("updated");
        assertThat(result).bodyJson().extractingPath("$.approval_timeout_hours").asNumber()
                .isEqualTo(48);
        assertThat(result).bodyJson().extractingPath("$.approvers[0].role").asString()
                .isEqualTo("ADMIN");

        var stored = approverRepository.findAllByReviewPlan_IdOrderByStageAsc(plan.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getRole()).isEqualTo(UserRoleType.ADMIN);
    }

    @Test
    void updateByAnalystReturns403() {
        var plan = savePlan(primaryOrg, "Plan");

        var result = mvc.put().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description":"x"}
                        """)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void updatePlanFromOtherOrgReturns404() {
        var plan = savePlan(otherOrg, "OtherPlan");

        var result = mvc.put().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description":"x"}
                        """)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void deleteRemovesUnusedPlan() {
        var plan = savePlan(primaryOrg, "Plan");
        saveApprover(plan, null, UserRoleType.REVIEWER, 1);

        var result = mvc.delete().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(reviewPlanRepository.findById(plan.getId())).isEmpty();
        assertThat(approverRepository.findAllByReviewPlan_Id(plan.getId())).isEmpty();
    }

    @Test
    void deletePlanInUseReturns409() {
        var plan = savePlan(primaryOrg, "Plan");
        saveDatasourceUsingPlan(primaryOrg, "DS", plan);

        var result = mvc.delete().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REVIEW_PLAN_IN_USE");
        assertThat(reviewPlanRepository.findById(plan.getId())).isPresent();
    }

    @Test
    void deletePlanFromOtherOrgReturns404() {
        var plan = savePlan(otherOrg, "OtherPlan");

        var result = mvc.delete().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    @Test
    void deleteByAnalystReturns403() {
        var plan = savePlan(primaryOrg, "Plan");

        var result = mvc.delete().uri("/api/v1/review-plans/" + plan.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
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

    private ReviewPlanEntity savePlan(OrganizationEntity org, String name) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName(name);
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        return reviewPlanRepository.save(plan);
    }

    private ReviewPlanApproverEntity saveApprover(ReviewPlanEntity plan, UserEntity user,
                                                  UserRoleType role, int stage) {
        var entity = new ReviewPlanApproverEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewPlan(plan);
        entity.setUser(user);
        entity.setRole(role);
        entity.setStage(stage);
        return approverRepository.save(entity);
    }

    private DatasourceEntity saveDatasourceUsingPlan(OrganizationEntity org, String name,
                                                    ReviewPlanEntity plan) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        ds.setReviewPlan(plan);
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
