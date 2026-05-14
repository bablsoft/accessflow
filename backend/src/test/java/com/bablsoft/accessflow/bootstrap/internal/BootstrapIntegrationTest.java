package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.ReviewPlanAdminService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class BootstrapIntegrationTest {

    @Autowired OrganizationProvisioningService organizationProvisioningService;
    @Autowired UserQueryService userQueryService;
    @Autowired ReviewPlanAdminService reviewPlanAdminService;
    @Autowired JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void bootstrapProperties(DynamicPropertyRegistry registry) throws Exception {
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

        // Bootstrap declarations
        registry.add("accessflow.bootstrap.enabled", () -> "true");
        registry.add("accessflow.bootstrap.organization.name", () -> "Acme Integration");
        registry.add("accessflow.bootstrap.admin.email", () -> "bootstrap-admin@acme.test");
        registry.add("accessflow.bootstrap.admin.display-name", () -> "Bootstrap Admin");
        registry.add("accessflow.bootstrap.admin.password", () -> "bootstrap-test-password");
        registry.add("accessflow.bootstrap.review-plans[0].name", () -> "bootstrap-default");
        registry.add("accessflow.bootstrap.review-plans[0].requires-ai-review", () -> "false");
        registry.add("accessflow.bootstrap.review-plans[0].requires-human-approval", () -> "true");
        registry.add("accessflow.bootstrap.review-plans[0].min-approvals-required", () -> "1");
        registry.add("accessflow.bootstrap.review-plans[0].approval-timeout-hours", () -> "24");
        registry.add("accessflow.bootstrap.review-plans[0].auto-approve-reads", () -> "false");
        registry.add("accessflow.bootstrap.review-plans[0].approver-emails[0]",
                () -> "bootstrap-admin@acme.test");
    }

    @AfterEach
    void cleanup() {
        // The Testcontainers Postgres is shared across @SpringBootTest classes in the same JVM,
        // so bootstrap-seeded rows would leak into other integration tests' @BeforeEach
        // deleteAll() chains. Strip review_plan_approvers + review_plans here so the FK from
        // approvers→users no longer blocks user deletion in downstream test setups; the rest of
        // the seeded rows (admin user, organization) are wiped by those tests' own deleteAll().
        jdbcTemplate.update("DELETE FROM review_plan_approvers");
        jdbcTemplate.update("DELETE FROM review_plans");
    }

    @Test
    void seedsOrganizationAdminAndReviewPlan() {
        var orgId = organizationProvisioningService.findBySlug("acme-integration").orElseThrow();
        assertThat(orgId).isNotNull();

        var admin = userQueryService.findByEmail("bootstrap-admin@acme.test").orElseThrow();
        assertThat(admin.organizationId()).isEqualTo(orgId);
        assertThat(admin.role().name()).isEqualTo("ADMIN");
        assertThat(admin.active()).isTrue();

        var reviewPlans = reviewPlanAdminService.list(orgId);
        assertThat(reviewPlans).hasSize(1);
        var plan = reviewPlans.get(0);
        assertThat(plan.name()).isEqualTo("bootstrap-default");
        assertThat(plan.minApprovalsRequired()).isEqualTo(1);
        assertThat(plan.approvers()).hasSize(1);
        assertThat(plan.approvers().get(0).userId()).isEqualTo(admin.id());
    }
}
