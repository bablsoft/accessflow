package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentMetricsControllerTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();

    private DeploymentMetricsService metricsService;
    private DeploymentMetricsController controller;

    @BeforeEach
    void setUp() {
        metricsService = mock(DeploymentMetricsService.class);
        controller = new DeploymentMetricsController(metricsService);
    }

    @Test
    void pipelineMetricsDelegatesTheCallerAndMapsEveryBlock() {
        when(metricsService.pipelineMetrics(pipelineId, orgId, userId,
                Set.of(Permission.DEPLOYMENT_REVIEW),
                new DeploymentMetricsFilter(FROM, TO, "prod", null))).thenReturn(view());

        var response = controller.pipelineMetrics(pipelineId, FROM, TO, "prod", auth());

        assertThat(response.period().from()).isEqualTo(FROM);
        assertThat(response.period().to()).isEqualTo(TO);
        assertThat(response.deploymentFrequency().total()).isEqualTo(4);
        assertThat(response.deploymentFrequency().perDay()).isEqualTo(4.0);
        assertThat(response.leadTime().p50Seconds()).isEqualTo(60L);
        assertThat(response.leadTime().p95Seconds()).isEqualTo(120L);
        assertThat(response.leadTime().sampleSize()).isEqualTo(4);
        assertThat(response.leadTime().source()).isEqualTo(LeadTimeSource.MIXED);
        assertThat(response.changeFailureRate().rate()).isEqualTo(0.5);
        assertThat(response.changeFailureRate().failed()).isEqualTo(1);
        assertThat(response.changeFailureRate().rolledBack()).isEqualTo(1);
        assertThat(response.changeFailureRate().unreported()).isEqualTo(1);
        assertThat(response.changeFailureRate().executed()).isEqualTo(4);
        assertThat(response.timeToRestore().p50Seconds()).isEqualTo(300L);
        assertThat(response.timeToRestore().restored()).isEqualTo(1);
        assertThat(response.timeToRestore().openIncidents()).isEqualTo(1);
        assertThat(response.approvalLatency().p95Seconds()).isNull();
        assertThat(response.approvalLatency().sampleSize()).isZero();
        assertThat(response.timeoutRate().rate()).isEqualTo(0.25);
        assertThat(response.timeoutRate().timedOut()).isEqualTo(1);
        assertThat(response.timeoutRate().enteredReview()).isEqualTo(4);
        assertThat(response.breakGlassCount()).isEqualTo(2);
        assertThat(response.daily()).singleElement().satisfies(day -> {
            assertThat(day.date()).isEqualTo(LocalDate.parse("2026-10-01"));
            assertThat(day.deployments()).isEqualTo(4);
            assertThat(day.failed()).isEqualTo(1);
            assertThat(day.rolledBack()).isEqualTo(1);
        });
    }

    @Test
    void pipelineMetricsPassesTheNotFoundThrough() {
        when(metricsService.pipelineMetrics(eq(pipelineId), eq(orgId), eq(userId), any(), any()))
                .thenThrow(new DeploymentPipelineNotFoundException(pipelineId));

        assertThatThrownBy(() -> controller.pipelineMetrics(pipelineId, null, null, null, auth()))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void organizationMetricsBuildsTheFilterFromTheSnakeCaseParams() {
        when(metricsService.organizationMetrics(orgId,
                new DeploymentMetricsFilter(FROM, TO, "prod", pipelineId))).thenReturn(view());

        var response = controller.organizationMetrics(FROM, TO, "prod", pipelineId, auth());

        assertThat(response.breakGlassCount()).isEqualTo(2);
    }

    @Test
    void onlyTheOrganizationWideEndpointCarriesAFunctionalGate() throws NoSuchMethodException {
        assertThat(DeploymentMetricsController.class.getAnnotation(PreAuthorize.class)).isNull();
        var pipelineMethod = DeploymentMetricsController.class.getDeclaredMethod(
                "pipelineMetrics", UUID.class, Instant.class, Instant.class, String.class,
                Authentication.class);
        assertThat(pipelineMethod.getAnnotation(PreAuthorize.class)).isNull();
        var orgMethod = DeploymentMetricsController.class.getDeclaredMethod(
                "organizationMetrics", Instant.class, Instant.class, String.class, UUID.class,
                Authentication.class);
        assertThat(orgMethod.getAnnotation(PreAuthorize.class).value())
                .contains("PERM_DEPLOYMENT_PIPELINE_MANAGE", "PERM_DEPLOYMENT_REVIEW",
                        "PERM_QUERY_ADMIN");
    }

    private Authentication auth() {
        var claims = new JwtClaims(userId, "reviewer@example.com", UserRoleType.REVIEWER,
                UUID.randomUUID(), "REVIEWER", Set.<Permission>of(Permission.DEPLOYMENT_REVIEW),
                orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }

    private static DeploymentMetricsView view() {
        return new DeploymentMetricsView(
                new DeploymentMetricsView.Period(FROM, TO),
                new DeploymentMetricsView.DeploymentFrequency(4, 4.0),
                new DeploymentMetricsView.LeadTime(60L, 120L, 4, LeadTimeSource.MIXED),
                new DeploymentMetricsView.ChangeFailureRate(0.5, 1, 1, 1, 4),
                new DeploymentMetricsView.TimeToRestore(300L, 300L, 1, 1),
                new DeploymentMetricsView.ApprovalLatency(null, null, 0),
                new DeploymentMetricsView.TimeoutRate(0.25, 1, 4),
                2,
                List.of(new DeploymentMetricsView.DailyBucket(LocalDate.parse("2026-10-01"), 4, 1,
                        1)));
    }
}
