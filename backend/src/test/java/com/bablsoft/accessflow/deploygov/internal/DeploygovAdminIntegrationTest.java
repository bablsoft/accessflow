package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentGroupPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the #688 admin surface end-to-end against Postgres: pipeline + environment CRUD,
 * user/group trigger grants with the effective-permission union, and freeze-window CRUD plus the
 * evaluator, all through the real services and repositories.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploygovAdminIntegrationTest {

    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired DeploymentFreezeWindowService freezeWindowService;
    @Autowired FreezeWindowEvaluator freezeWindowEvaluator;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentFreezeWindowRepository freezeWindowRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired DeploymentPipelineGroupPermissionRepository groupPermissionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserGroupMembershipRepository membershipRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;

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

    // These tests commit rows into the shared Testcontainers DB. The
    // deployment_pipeline_group_permissions.created_by FK to users has no ON DELETE, so leaving
    // rows behind would make a later integration test's userRepository.deleteAll() fail. Clear
    // everything this test creates, children first.
    @AfterEach
    void cleanup() {
        userPermissionRepository.deleteAll();
        groupPermissionRepository.deleteAll();
        freezeWindowRepository.deleteAll();
        environmentRepository.deleteAll();
        pipelineRepository.deleteAll();
        membershipRepository.deleteAll();
        userGroupRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void pipelineCrudRoundTripsWithDuplicateNameGuardAndOrgScoping() {
        var org = saveOrg();
        var otherOrg = saveOrg();

        var created = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-api", PipelineProvider.GITHUB_ACTIONS, "https://git.example/payments",
                "acme/payments", null, null, null));
        assertThat(created.active()).isTrue();
        assertThat(created.aiAnalysisEnabled()).isTrue();

        assertThatThrownBy(() -> pipelineService.create(new CreateDeploymentPipelineCommand(
                org.getId(), "payments-api", PipelineProvider.GENERIC, null, null, null, null,
                null)))
                .isInstanceOf(DuplicateDeploymentPipelineNameException.class);

        var updated = pipelineService.update(created.id(), org.getId(),
                new UpdateDeploymentPipelineCommand(null, PipelineProvider.GITLAB_CI, null, null,
                        null, null, false, null, null, null));
        assertThat(updated.provider()).isEqualTo(PipelineProvider.GITLAB_CI);
        assertThat(updated.aiAnalysisEnabled()).isFalse();

        // Cross-org access reads as 404, never as "exists elsewhere".
        assertThatThrownBy(() -> pipelineService.get(created.id(), otherOrg.getId()))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);

        assertThat(pipelineService.list(org.getId(), PageRequest.of(0, 20)).content()).hasSize(1);

        pipelineService.delete(created.id(), org.getId());
        assertThat(pipelineRepository.findById(created.id())).isEmpty();
    }

    @Test
    void environmentCrudOrdersBySortOrderAndCascadesWithPipeline() {
        var org = saveOrg();
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "p-" + UUID.randomUUID(), PipelineProvider.GENERIC, null, null, null, null, null));

        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 2, true, 2, null, false));
        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("staging", 1, false, null, null, true));

        var environments = pipelineService.listEnvironments(pipeline.id(), org.getId());
        assertThat(environments).extracting(v -> v.name())
                .containsExactly("staging", "production");

        pipelineService.delete(pipeline.id(), org.getId());
        assertThat(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipeline.id()))
                .isEmpty();
    }

    @Test
    void effectivePermissionUnionsDirectAndGroupGrants() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var member = saveUser(org, UserRoleType.ANALYST);
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "p-" + UUID.randomUUID(), PipelineProvider.GENERIC, null, null, null, null, null));
        var group = saveGroup(org);
        addToGroup(member, group);

        permissionService.grantPermission(pipeline.id(), org.getId(), admin.getId(),
                new GrantDeploymentPermissionCommand(member.getId(), true, false,
                        Instant.now().plus(1, ChronoUnit.HOURS)));
        permissionService.grantGroupPermission(pipeline.id(), org.getId(), admin.getId(),
                new GrantDeploymentGroupPermissionCommand(group.getId(), false, true, null));

        var effective = permissionService.effectivePermission(pipeline.id(), member.getId())
                .orElseThrow();

        assertThat(effective.canTrigger()).isTrue();
        assertThat(effective.canBreakGlass()).isTrue();
        assertThat(effective.expiresAt()).isNull();
    }

    @Test
    void expiredGroupGrantDoesNotContributeToTheUnion() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var member = saveUser(org, UserRoleType.ANALYST);
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "p-" + UUID.randomUUID(), PipelineProvider.GENERIC, null, null, null, null, null));
        var group = saveGroup(org);
        addToGroup(member, group);

        permissionService.grantGroupPermission(pipeline.id(), org.getId(), admin.getId(),
                new GrantDeploymentGroupPermissionCommand(group.getId(), true, true,
                        Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(permissionService.effectivePermission(pipeline.id(), member.getId())).isEmpty();
    }

    @Test
    void permissionGrantUpsertsAndUpdatePreservesProvenance() {
        var org = saveOrg();
        var admin = saveUser(org, UserRoleType.ADMIN);
        var otherAdmin = saveUser(org, UserRoleType.ADMIN);
        var target = saveUser(org, UserRoleType.ANALYST);
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "p-" + UUID.randomUUID(), PipelineProvider.GENERIC, null, null, null, null, null));

        var granted = permissionService.grantPermission(pipeline.id(), org.getId(), admin.getId(),
                new GrantDeploymentPermissionCommand(target.getId(), true, false, null));
        var regranted = permissionService.grantPermission(pipeline.id(), org.getId(),
                otherAdmin.getId(),
                new GrantDeploymentPermissionCommand(target.getId(), true, true, null));
        assertThat(regranted.id()).isEqualTo(granted.id());

        var updated = permissionService.updatePermission(pipeline.id(), org.getId(), granted.id(),
                new UpdateDeploymentPermissionCommand(false, false, null));
        assertThat(updated.canTrigger()).isFalse();
        assertThat(updated.userEmail()).isEqualTo(target.getEmail());

        // The original grantor survives both the upsert and the in-place update.
        var entity = userPermissionRepository.findById(granted.id()).orElseThrow();
        assertThat(entity.getCreatedBy()).isEqualTo(admin.getId());

        permissionService.revokePermission(pipeline.id(), org.getId(), granted.id());
        assertThat(userPermissionRepository.findById(granted.id())).isEmpty();
    }

    @Test
    void freezeWindowCrudRoundTripsAndEvaluatorHonoursScopeAndSchedule() {
        var org = saveOrg();
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "p-" + UUID.randomUUID(), PipelineProvider.GENERIC, null, null, null, null, null));
        var environment = pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 0, true, null, null, false));

        // Recurring Friday-evening window in Berlin, scoped to the environment.
        var recurring = freezeWindowService.create(new DeploymentFreezeWindowCommand(org.getId(),
                pipeline.id(), environment.id(), null, null, List.of(5), LocalTime.of(18, 0),
                LocalTime.of(23, 0), "Europe/Berlin", FreezeBehavior.HOLD, "friday freeze", null));
        assertThat(recurring.daysOfWeek()).containsExactly(5);

        // 2026-08-21 is a Friday; 19:00 UTC = 21:00 in Berlin — inside the window.
        var fridayEvening = Instant.parse("2026-08-21T19:00:00Z");
        var freeze = freezeWindowEvaluator.evaluate(org.getId(), pipeline.id(), environment.id(),
                fridayEvening).orElseThrow();
        assertThat(freeze.windowId()).isEqualTo(recurring.id());
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);

        // The same instant on another pipeline's coordinates is not frozen.
        assertThat(freezeWindowEvaluator.evaluate(org.getId(), UUID.randomUUID(), null,
                fridayEvening)).isEmpty();

        // Monday noon is outside the recurring window.
        assertThat(freezeWindowEvaluator.evaluate(org.getId(), pipeline.id(), environment.id(),
                Instant.parse("2026-08-24T12:00:00Z"))).isEmpty();

        // Full replacement flips the window to a one-off org-wide REJECT freeze.
        var replaced = freezeWindowService.update(recurring.id(),
                new DeploymentFreezeWindowCommand(org.getId(), null, null,
                        Instant.parse("2026-12-24T00:00:00Z"),
                        Instant.parse("2027-01-02T00:00:00Z"), null, null, null, null,
                        FreezeBehavior.REJECT, "holiday freeze", null));
        assertThat(replaced.daysOfWeek()).isNull();
        assertThat(freezeWindowEvaluator.evaluate(org.getId(), UUID.randomUUID(), null,
                        Instant.parse("2026-12-25T12:00:00Z")).orElseThrow().behavior())
                .isEqualTo(FreezeBehavior.REJECT);

        freezeWindowService.delete(recurring.id(), org.getId());
        assertThat(freezeWindowRepository.findById(recurring.id())).isEmpty();
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("User");
        user.setPasswordHash("x");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private UserGroupEntity saveGroup(OrganizationEntity org) {
        var group = new UserGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganization(org);
        group.setName("Group-" + UUID.randomUUID());
        return userGroupRepository.save(group);
    }

    private void addToGroup(UserEntity user, UserGroupEntity group) {
        var membership = new UserGroupMembershipEntity();
        membership.setId(new UserGroupMembershipEntity.Id(user.getId(), group.getId()));
        membership.setUser(user);
        membership.setGroup(group);
        membershipRepository.save(membership);
    }
}
