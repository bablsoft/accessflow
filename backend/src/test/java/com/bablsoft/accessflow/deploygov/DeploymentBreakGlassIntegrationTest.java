package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentBreakGlassNotAllowedException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-glass deployment submission end-to-end (#692): the force-approve, the suppressed AI
 * analysis, the synchronously opened retro-review in {@code break_glass_events}, the prominent
 * audit row, and both denial branches. The retro-review listener is a plain synchronous
 * {@code @EventListener}, so every assertion here can be immediate.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentBreakGlassIntegrationTest {

    @Autowired DeploymentRequestService requestService;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM break_glass_events");
        requestRepository.deleteAll();
        userPermissionRepository.deleteAll();
        environmentRepository.deleteAll();
        pipelineRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void breakGlassForceApprovesOpensRetroReviewAndAudits() {
        var fixture = fixture(true, true);

        var result = requestService.submit(breakGlassCommand(fixture));

        assertThat(result.replay()).isFalse();
        assertThat(result.request().status()).isEqualTo(QueryStatus.APPROVED);
        assertThat(result.request().submissionReason()).isEqualTo(SubmissionReason.EMERGENCY_ACCESS);
        // The submitted event was suppressed, so no analysis ever runs despite ai_analysis_enabled.
        assertThat(result.request().aiAnalysisId()).isNull();

        var requestId = result.request().id();
        var entity = requestRepository.findById(requestId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(entity.getAiAnalysisId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analyses WHERE deployment_request_id = ?",
                Long.class, requestId)).isZero();

        // The mandatory retro-review committed synchronously with the force-approve.
        var retroReview = jdbcTemplate.queryForMap(
                "SELECT status::text AS status, pipeline_id FROM break_glass_events "
                        + "WHERE deployment_request_id = ?", requestId);
        assertThat(retroReview.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(retroReview.get("pipeline_id")).isEqualTo(fixture.pipelineId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_id = ?",
                Long.class, "DEPLOYMENT_BREAK_GLASS_EXECUTED", requestId)).isEqualTo(1);
    }

    @Test
    void deniedWithoutABreakGlassGrant() {
        var fixture = fixture(false, true);

        assertThatThrownBy(() -> requestService.submit(breakGlassCommand(fixture)))
                .isInstanceOf(DeploymentBreakGlassNotAllowedException.class);

        assertThat(requestRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM break_glass_events", Long.class)).isZero();
    }

    @Test
    void deniedWhenTheEnvironmentDisallowsBreakGlass() {
        var fixture = fixture(true, false);

        assertThatThrownBy(() -> requestService.submit(breakGlassCommand(fixture)))
                .isInstanceOf(DeploymentBreakGlassNotAllowedException.class);

        assertThat(requestRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM break_glass_events", Long.class)).isZero();
    }

    private SubmitDeploymentRequestCommand breakGlassCommand(Fixture fixture) {
        return new SubmitDeploymentRequestCommand(fixture.pipelineId(), "production",
                fixture.orgId(), fixture.userId(), false, "2.4.1", "abc123", "ghcr.io/app:2.4.1",
                "https://ci/run/1", "run-bg-" + UUID.randomUUID(), Map.of("changelog", "hotfix"),
                "prod is on fire", null, SubmissionReason.EMERGENCY_ACCESS, "10.0.0.1");
    }

    private record Fixture(UUID orgId, UUID userId, UUID pipelineId) {
    }

    private Fixture fixture(boolean canBreakGlass, boolean allowBreakGlass) {
        var org = saveOrg();
        var user = saveUser(org);
        // AI analysis deliberately on — a break-glass submit must never reach the analyzer anyway.
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null, null,
                true, null));
        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 2, null,
                        allowBreakGlass));
        permissionService.grantPermission(pipeline.id(), org.getId(), user.getId(),
                new GrantDeploymentPermissionCommand(user.getId(), true, canBreakGlass, null));
        return new Fixture(org.getId(), user.getId(), pipeline.id());
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("Deployer");
        user.setPasswordHash("x");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }
}
