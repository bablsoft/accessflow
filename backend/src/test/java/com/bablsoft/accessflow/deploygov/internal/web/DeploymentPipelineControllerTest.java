package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentGroupPermissionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentPipelineControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();

    private DeploymentPipelineAdminService pipelineService;
    private DeploymentPermissionService permissionService;
    private DeploymentPipelineController controller;

    @BeforeEach
    void setUp() {
        pipelineService = mock(DeploymentPipelineAdminService.class);
        permissionService = mock(DeploymentPermissionService.class);
        controller = new DeploymentPipelineController(pipelineService, permissionService);
    }

    @Test
    void listAdaptsPageableAndMapsPage() {
        when(pipelineService.list(eq(orgId), any()))
                .thenReturn(new PageResponse<>(List.of(pipelineView()), 0, 20, 1, 1));

        var response = controller.list(auth(), Pageable.ofSize(20));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).name()).isEqualTo("payments-api");
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void getMapsView() {
        when(pipelineService.get(pipelineId, orgId)).thenReturn(pipelineView());

        var response = controller.get(pipelineId, auth());

        assertThat(response.id()).isEqualTo(pipelineId);
        assertThat(response.provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
    }

    @Test
    void createDelegatesWithCallersOrganization() {
        when(pipelineService.create(any())).thenReturn(pipelineView());

        var body = new CreateDeploymentPipelineRequest("payments-api",
                PipelineProvider.GITHUB_ACTIONS, null, null, null, null, null);
        var response = controller.create(body, auth());

        assertThat(response.name()).isEqualTo("payments-api");
        verify(pipelineService).create(org.mockito.ArgumentMatchers.argThat(
                c -> orgId.equals(c.organizationId()) && "payments-api".equals(c.name())));
    }

    @Test
    void updateDelegates() {
        when(pipelineService.update(eq(pipelineId), eq(orgId), any())).thenReturn(pipelineView());

        var body = new UpdateDeploymentPipelineRequest("renamed", null, null, null, null, null,
                null, null, null, null);
        var response = controller.update(pipelineId, body, auth());

        assertThat(response.id()).isEqualTo(pipelineId);
        verify(pipelineService).update(eq(pipelineId), eq(orgId),
                org.mockito.ArgumentMatchers.argThat(c -> "renamed".equals(c.name())));
    }

    @Test
    void deleteDelegates() {
        controller.delete(pipelineId, auth());

        verify(pipelineService).delete(pipelineId, orgId);
    }

    @Test
    void listEnvironmentsMapsViews() {
        when(pipelineService.listEnvironments(pipelineId, orgId))
                .thenReturn(List.of(environmentView()));

        var response = controller.listEnvironments(pipelineId, auth());

        assertThat(response).hasSize(1);
        assertThat(response.get(0).name()).isEqualTo("production");
    }

    @Test
    void createEnvironmentDelegates() {
        when(pipelineService.createEnvironment(eq(pipelineId), eq(orgId), any()))
                .thenReturn(environmentView());

        var body = new CreateDeploymentEnvironmentRequest("production", 1, true, 2, null, false,
                null);
        var response = controller.createEnvironment(pipelineId, body, auth());

        assertThat(response.name()).isEqualTo("production");
        verify(pipelineService).createEnvironment(eq(pipelineId), eq(orgId),
                org.mockito.ArgumentMatchers.argThat(c -> "production".equals(c.name())
                        && Integer.valueOf(2).equals(c.requiredApprovals())));
    }

    @Test
    void updateEnvironmentDelegates() {
        var environmentId = UUID.randomUUID();
        when(pipelineService.updateEnvironment(eq(pipelineId), eq(orgId), eq(environmentId), any()))
                .thenReturn(environmentView());

        var body = new UpdateDeploymentEnvironmentRequest(null, 3, null, null, true, null, null,
                null, null);
        controller.updateEnvironment(pipelineId, environmentId, body, auth());

        verify(pipelineService).updateEnvironment(eq(pipelineId), eq(orgId), eq(environmentId),
                org.mockito.ArgumentMatchers.argThat(
                        c -> Boolean.TRUE.equals(c.clearRequiredApprovals())));
    }

    @Test
    void deleteEnvironmentDelegates() {
        var environmentId = UUID.randomUUID();
        controller.deleteEnvironment(pipelineId, environmentId, auth());

        verify(pipelineService).deleteEnvironment(pipelineId, orgId, environmentId);
    }

    @Test
    void listPermissionsMapsViews() {
        when(permissionService.listPermissions(pipelineId, orgId))
                .thenReturn(List.of(permissionView()));

        var response = controller.listPermissions(pipelineId, auth());

        assertThat(response).hasSize(1);
        assertThat(response.get(0).userEmail()).isEqualTo("dev@acme.test");
    }

    @Test
    void grantDelegatesWithCallerAsGrantor() {
        var userId = UUID.randomUUID();
        when(permissionService.grantPermission(eq(pipelineId), eq(orgId), eq(adminId), any()))
                .thenReturn(permissionView());

        var body = new GrantDeploymentPermissionRequest(userId, true, null, null);
        var response = controller.grant(pipelineId, body, auth());

        assertThat(response.canTrigger()).isTrue();
        verify(permissionService).grantPermission(eq(pipelineId), eq(orgId), eq(adminId),
                org.mockito.ArgumentMatchers.argThat(c -> userId.equals(c.userId())
                        && c.canTrigger() && !c.canBreakGlass()));
    }

    @Test
    void updatePermissionDelegates() {
        var permissionId = UUID.randomUUID();
        when(permissionService.updatePermission(eq(pipelineId), eq(orgId), eq(permissionId), any()))
                .thenReturn(permissionView());

        controller.updatePermission(pipelineId, permissionId,
                new UpdateDeploymentPermissionRequest(null, true, null), auth());

        verify(permissionService).updatePermission(eq(pipelineId), eq(orgId), eq(permissionId),
                org.mockito.ArgumentMatchers.argThat(c -> !c.canTrigger() && c.canBreakGlass()));
    }

    @Test
    void revokeDelegates() {
        var permissionId = UUID.randomUUID();
        controller.revoke(pipelineId, permissionId, auth());

        verify(permissionService).revokePermission(pipelineId, orgId, permissionId);
    }

    @Test
    void listGroupPermissionsMapsViews() {
        when(permissionService.listGroupPermissions(pipelineId, orgId))
                .thenReturn(List.of(groupPermissionView()));

        var response = controller.listGroupPermissions(pipelineId, auth());

        assertThat(response).hasSize(1);
        assertThat(response.get(0).groupName()).isEqualTo("platform");
    }

    @Test
    void grantGroupDelegatesWithCallerAsGrantor() {
        var groupId = UUID.randomUUID();
        when(permissionService.grantGroupPermission(eq(pipelineId), eq(orgId), eq(adminId), any()))
                .thenReturn(groupPermissionView());

        controller.grantGroup(pipelineId,
                new GrantDeploymentGroupPermissionRequest(groupId, true, true, null), auth());

        verify(permissionService).grantGroupPermission(eq(pipelineId), eq(orgId), eq(adminId),
                org.mockito.ArgumentMatchers.argThat(c -> groupId.equals(c.groupId())
                        && c.canBreakGlass()));
    }

    @Test
    void updateGroupPermissionDelegates() {
        var permissionId = UUID.randomUUID();
        when(permissionService.updateGroupPermission(eq(pipelineId), eq(orgId), eq(permissionId),
                any())).thenReturn(groupPermissionView());

        controller.updateGroup(pipelineId, permissionId,
                new UpdateDeploymentGroupPermissionRequest(true, null, null), auth());

        verify(permissionService).updateGroupPermission(eq(pipelineId), eq(orgId),
                eq(permissionId), org.mockito.ArgumentMatchers.argThat(
                        c -> c.canTrigger() && !c.canBreakGlass()));
    }

    @Test
    void revokeGroupDelegates() {
        var permissionId = UUID.randomUUID();
        controller.revokeGroup(pipelineId, permissionId, auth());

        verify(permissionService).revokeGroupPermission(pipelineId, orgId, permissionId);
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private DeploymentPipelineView pipelineView() {
        return new DeploymentPipelineView(pipelineId, orgId, "payments-api",
                PipelineProvider.GITHUB_ACTIONS, null, null, null, true, null, true,
                Instant.now(), Instant.now());
    }

    private DeploymentEnvironmentView environmentView() {
        return new DeploymentEnvironmentView(UUID.randomUUID(), pipelineId, "production", 1, true,
                2, null, false, Instant.now(), List.of());
    }

    private DeploymentPermissionView permissionView() {
        return new DeploymentPermissionView(UUID.randomUUID(), pipelineId, UUID.randomUUID(),
                "dev@acme.test", "Dev", true, false, null, Instant.now());
    }

    private DeploymentGroupPermissionView groupPermissionView() {
        return new DeploymentGroupPermissionView(UUID.randomUUID(), pipelineId, UUID.randomUUID(),
                "platform", 4L, true, true, null, Instant.now());
    }
}
