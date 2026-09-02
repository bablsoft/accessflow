package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionInventoryService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentVersionInventoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private DeploymentVersionInventoryService inventoryService;
    private DeploymentVersionInventoryController controller;

    @BeforeEach
    void setUp() {
        inventoryService = mock(DeploymentVersionInventoryService.class);
        controller = new DeploymentVersionInventoryController(inventoryService);
    }

    @Test
    void pipelineMatrixDelegatesTheCallerAndMapsRows() {
        when(inventoryService.pipelineMatrix(pipelineId, orgId, userId,
                Set.of(Permission.DEPLOYMENT_REVIEW))).thenReturn(List.of(versionView()));

        var matrix = controller.pipelineMatrix(pipelineId, auth());

        assertThat(matrix).hasSize(1);
        var row = matrix.getFirst();
        assertThat(row.pipelineId()).isEqualTo(pipelineId);
        assertThat(row.environment().id()).isEqualTo(environmentId);
        assertThat(row.environment().tags()).containsExactly("prod", "acme");
        assertThat(row.environment().sortOrder()).isEqualTo(3);
        assertThat(row.currentVersion()).isEqualTo("2.4.0");
        assertThat(row.drift().latestVersion()).isEqualTo("2.4.1");
        assertThat(row.drift().drifted()).isTrue();
        assertThat(row.drift().daysBehind()).isEqualTo(4L);
        assertThat(row.drift().deploymentsBehind()).isEqualTo(1L);
    }

    @Test
    void listBuildsTheFilterFromTheSnakeCaseParams() {
        when(inventoryService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(versionView()), 0, 20, 1, 1));

        var page = controller.list(pipelineId, "acme", "prod", true, auth(), Pageable.ofSize(20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        var filterCaptor = ArgumentCaptor.forClass(DeploymentEnvironmentVersionListFilter.class);
        verify(inventoryService).list(filterCaptor.capture(), any());
        var filter = filterCaptor.getValue();
        assertThat(filter.organizationId()).isEqualTo(orgId);
        assertThat(filter.pipelineId()).isEqualTo(pipelineId);
        assertThat(filter.tag()).isEqualTo("acme");
        assertThat(filter.environment()).isEqualTo("prod");
        assertThat(filter.drifted()).isTrue();
    }

    @Test
    void listAdaptsThePageable() {
        when(inventoryService.list(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        controller.list(null, null, null, null, auth(), Pageable.ofSize(5).withPage(2));

        verify(inventoryService).list(any(),
                eq(com.bablsoft.accessflow.core.api.PageRequest.of(2, 5)));
    }

    @Test
    void historyDelegatesAndMapsEntries() {
        when(inventoryService.history(eq(pipelineId), eq(environmentId), eq(QueryStatus.EXECUTED),
                eq(orgId), eq(userId), eq(Set.of(Permission.DEPLOYMENT_REVIEW)), any()))
                .thenReturn(new PageResponse<>(List.of(historyView()), 0, 20, 1, 1));

        var page = controller.history(pipelineId, environmentId, QueryStatus.EXECUTED, auth(),
                Pageable.ofSize(20));

        assertThat(page.totalElements()).isEqualTo(1);
        var entry = page.content().getFirst();
        assertThat(entry.version()).isEqualTo("2.4.1");
        assertThat(entry.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(entry.executedAt()).isEqualTo(NOW);
    }

    private Authentication auth() {
        var claims = new JwtClaims(userId, "reviewer@example.com", UserRoleType.REVIEWER,
                UUID.randomUUID(), "REVIEWER", Set.<Permission>of(Permission.DEPLOYMENT_REVIEW),
                orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }

    private DeploymentEnvironmentVersionView versionView() {
        return new DeploymentEnvironmentVersionView(pipelineId, "payments-api", environmentId,
                "prod-acme", List.of("prod", "acme"), 3, "2.4.0", UUID.randomUUID(),
                NOW.minusSeconds(4 * 86_400), "2.3.9", DeploymentOutcome.SUCCEEDED,
                new DeploymentVersionDriftView("2.4.1", NOW, true, 4L, 1L));
    }

    private DeploymentVersionHistoryEntryView historyView() {
        return new DeploymentVersionHistoryEntryView(UUID.randomUUID(), "2.4.1",
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED, NOW, UUID.randomUUID(),
                SubmissionReason.USER_SUBMITTED, "abc123", "https://ci.example.com/run/1",
                NOW.minusSeconds(600), NOW);
    }
}
