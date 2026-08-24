package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyView;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDeploymentRoutingPolicyControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    private DeploymentRoutingPolicyService routingPolicyService;
    private AdminDeploymentRoutingPolicyController controller;

    @BeforeEach
    void setUp() {
        routingPolicyService = mock(DeploymentRoutingPolicyService.class);
        controller = new AdminDeploymentRoutingPolicyController(routingPolicyService);
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/deployment-routing-policies");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theControllerIsGatedByThePipelineManagePermission() {
        var preAuthorize = AdminDeploymentRoutingPolicyController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')");
    }

    @Test
    void listMapsEveryPolicy() {
        when(routingPolicyService.list(orgId)).thenReturn(List.of(view()));

        var responses = controller.list(auth());

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).name()).isEqualTo("freeze fridays");
        assertThat(responses.get(0).conditions().environments()).containsExactly("production");
        assertThat(responses.get(0).conditions().minRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void getMapsThePolicy() {
        when(routingPolicyService.get(policyId, orgId)).thenReturn(view());

        assertThat(controller.get(policyId, auth()).id()).isEqualTo(policyId);
    }

    @Test
    void createAnswers201WithALocationHeader() {
        when(routingPolicyService.create(any())).thenReturn(view());

        var response = controller.create(new CreateDeploymentRoutingPolicyRequest(null,
                "freeze fridays", null, DeploymentRoutingAction.AUTO_REJECT, null, null, null),
                auth());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).asString().endsWith("/" + policyId);
        var captor = ArgumentCaptor.forClass(CreateDeploymentRoutingPolicyCommand.class);
        verify(routingPolicyService).create(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().conditions()).isEqualTo(DeploymentRoutingConditions.NONE);
        assertThat(captor.getValue().priority()).isEqualTo(100);
        assertThat(captor.getValue().enabled()).isTrue();
    }

    @Test
    void createPassesTheSuppliedConditionsAndFlags() {
        when(routingPolicyService.create(any())).thenReturn(view());
        var conditions = new DeploymentRoutingConditionsRequest(List.of("production"), null,
                RiskLevel.HIGH, List.of("2.*"), Set.of(5), LocalTime.of(16, 0), LocalTime.of(23, 0),
                "Europe/Berlin");

        controller.create(new CreateDeploymentRoutingPolicyRequest(null, "freeze fridays", conditions,
                DeploymentRoutingAction.REQUIRE_APPROVALS, 2, 10, false), auth());

        var captor = ArgumentCaptor.forClass(CreateDeploymentRoutingPolicyCommand.class);
        verify(routingPolicyService).create(captor.capture());
        assertThat(captor.getValue().conditions().versionGlobs()).containsExactly("2.*");
        assertThat(captor.getValue().conditions().timezone()).isEqualTo("Europe/Berlin");
        assertThat(captor.getValue().requiredApprovals()).isEqualTo(2);
        assertThat(captor.getValue().priority()).isEqualTo(10);
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    void updateForwardsNullsAsUnchanged() {
        when(routingPolicyService.update(eq(policyId), eq(orgId), any())).thenReturn(view());

        var response = controller.update(policyId, new UpdateDeploymentRoutingPolicyRequest(null,
                true, "renamed", null, null, null, 20, false), auth());

        assertThat(response.id()).isEqualTo(policyId);
        var captor = ArgumentCaptor.forClass(UpdateDeploymentRoutingPolicyCommand.class);
        verify(routingPolicyService).update(eq(policyId), eq(orgId), captor.capture());
        assertThat(captor.getValue().clearPipeline()).isTrue();
        assertThat(captor.getValue().name()).isEqualTo("renamed");
        assertThat(captor.getValue().conditions()).isNull();
        assertThat(captor.getValue().priority()).isEqualTo(20);
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    void deleteDelegatesToTheService() {
        controller.delete(policyId, auth());

        verify(routingPolicyService).delete(policyId, orgId);
    }

    private DeploymentRoutingPolicyView view() {
        return new DeploymentRoutingPolicyView(policyId, orgId, null, "freeze fridays",
                new DeploymentRoutingConditions(List.of("production"), null, RiskLevel.HIGH, null,
                        null, null, null, null),
                DeploymentRoutingAction.AUTO_REJECT, null, 10, true,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Authentication auth() {
        var claims = new JwtClaims(UUID.randomUUID(), "admin@example.com", UserRoleType.ADMIN,
                UUID.randomUUID(), "ADMIN", Set.of(), orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }
}
