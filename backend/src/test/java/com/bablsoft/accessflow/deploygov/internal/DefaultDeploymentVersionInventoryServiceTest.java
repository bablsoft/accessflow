package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentVersionExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentVersionInventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();

    private DeploymentEnvironmentVersionRepository versionRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DeploymentRequestRepository requestRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private DefaultDeploymentVersionInventoryService service;

    @BeforeEach
    void setUp() {
        versionRepository = mock(DeploymentEnvironmentVersionRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        requestRepository = mock(DeploymentRequestRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        service = new DefaultDeploymentVersionInventoryService(versionRepository,
                environmentRepository, pipelineRepository, requestRepository, permissionResolver);
        when(permissionResolver.resolve(any(), any())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------- permission matrix

    @Test
    void pipelineMatrixIsVisibleToEachOfTheThreeFunctionalPermissions() {
        var pipeline = pipeline("payments-api");
        stubMatrixCollaborators(pipeline, List.of(), List.of());

        for (var permission : List.of(Permission.DEPLOYMENT_PIPELINE_MANAGE,
                Permission.DEPLOYMENT_REVIEW, Permission.QUERY_ADMIN)) {
            assertThat(service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                    Set.of(permission))).isEmpty();
        }
    }

    @Test
    void pipelineMatrixIsVisibleToAnEffectiveTriggerGrantHolder() {
        var pipeline = pipeline("payments-api");
        stubMatrixCollaborators(pipeline, List.of(), List.of());
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, true, false, null)));

        assertThat(service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID, Set.of())).isEmpty();
    }

    @Test
    void pipelineMatrixReadsAsNotFoundWithoutAnyGrant() {
        var pipeline = pipeline("payments-api");
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, false, true, null)));

        assertThatThrownBy(() -> service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_SUBMIT_SELECT)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void pipelineMatrixReadsAsNotFoundAcrossOrganizations() {
        var pipelineId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pipelineMatrix(pipelineId, ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_PIPELINE_MANAGE)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void historyThrowsEnvironmentNotFoundForAnEnvironmentOnAnotherPipeline() {
        var pipeline = pipeline("payments-api");
        var strayEnvironment = environment(UUID.randomUUID(), "prod", 0);
        when(environmentRepository.findById(strayEnvironment.getId()))
                .thenReturn(Optional.of(strayEnvironment));

        assertThatThrownBy(() -> service.history(pipeline.getId(), strayEnvironment.getId(), null,
                ORG_ID, CALLER_ID, Set.of(Permission.DEPLOYMENT_REVIEW), PageRequest.of(0, 20)))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    // ---------------------------------------------------------------- per-pipeline matrix

    @Test
    void pipelineMatrixListsEveryEnvironmentInSortOrderIncludingNeverDeployedOnes() {
        var pipeline = pipeline("payments-api");
        var staging = environment(pipeline.getId(), "staging", 0);
        var prod = environment(pipeline.getId(), "prod", 1);
        var prodRow = row(pipeline.getId(), prod.getId(), "2.4.1", NOW, null);
        stubMatrixCollaborators(pipeline, List.of(staging, prod), List.of(prodRow));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix).hasSize(2);
        assertThat(matrix.get(0).environmentName()).isEqualTo("staging");
        assertThat(matrix.get(0).currentVersion()).isNull();
        assertThat(matrix.get(0).deployedAt()).isNull();
        assertThat(matrix.get(0).drift().drifted()).isTrue();
        assertThat(matrix.get(0).drift().daysBehind()).isNull();
        assertThat(matrix.get(0).drift().deploymentsBehind()).isNull();
        assertThat(matrix.get(1).environmentName()).isEqualTo("prod");
        assertThat(matrix.get(1).currentVersion()).isEqualTo("2.4.1");
        assertThat(matrix.get(1).drift().drifted()).isFalse();
        assertThat(matrix.get(1).pipelineName()).isEqualTo("payments-api");
    }

    @Test
    void neverDeployedEnvironmentIsNotDriftedWhenThePipelineHasNoSuccessfulDeploy() {
        var pipeline = pipeline("payments-api");
        var staging = environment(pipeline.getId(), "staging", 0);
        stubMatrixCollaborators(pipeline, List.of(staging), List.of());

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix.getFirst().drift().drifted()).isFalse();
        assertThat(matrix.getFirst().drift().latestVersion()).isNull();
        assertThat(matrix.getFirst().drift().daysBehind()).isZero();
        assertThat(matrix.getFirst().drift().deploymentsBehind()).isZero();
    }

    @Test
    void matrixRowsCarryTheEnvironmentTags() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod-acme", 0);
        prod.setTags(new String[] {"prod", "acme"});
        stubMatrixCollaborators(pipeline, List.of(prod), List.of());

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix.getFirst().tags()).containsExactly("prod", "acme");
        assertThat(matrix.getFirst().sortOrder()).isZero();
    }

    // ---------------------------------------------------------------- drift math

    @Test
    void latestIsTheNewestSuccessfulRowAndStringInequalityDrifts() {
        var pipeline = pipeline("payments-api");
        var staging = environment(pipeline.getId(), "staging", 0);
        var prod = environment(pipeline.getId(), "prod", 1);
        var stagingRow = row(pipeline.getId(), staging.getId(), "2.4.1", NOW, null);
        var prodRow = row(pipeline.getId(), prod.getId(), "2.4.0", NOW.minusSeconds(4 * 86_400),
                DeploymentOutcome.SUCCEEDED);
        stubMatrixCollaborators(pipeline, List.of(staging, prod), List.of(stagingRow, prodRow));
        when(requestRepository.findSuccessfulVersionExecutions(pipeline.getId(),
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED))
                .thenReturn(List.of(new DeploymentVersionExecution("2.4.1", NOW),
                        new DeploymentVersionExecution("2.4.0", NOW.minusSeconds(4 * 86_400))));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        var prodView = matrix.get(1);
        assertThat(prodView.drift().latestVersion()).isEqualTo("2.4.1");
        assertThat(prodView.drift().latestDeployedAt()).isEqualTo(NOW);
        assertThat(prodView.drift().drifted()).isTrue();
        assertThat(prodView.drift().daysBehind()).isEqualTo(4L);
        assertThat(prodView.drift().deploymentsBehind()).isEqualTo(1L);
        // The environment already running the latest version is never "behind".
        assertThat(matrix.get(0).drift().drifted()).isFalse();
        assertThat(matrix.get(0).drift().daysBehind()).isZero();
        assertThat(matrix.get(0).drift().deploymentsBehind()).isZero();
    }

    @Test
    void latestSkipsRowsWhoseLastOutcomeIsFailedOrRolledBack() {
        var pipeline = pipeline("payments-api");
        var a = environment(pipeline.getId(), "a", 0);
        var b = environment(pipeline.getId(), "b", 1);
        var c = environment(pipeline.getId(), "c", 2);
        var failed = row(pipeline.getId(), a.getId(), "3.0.0", NOW, DeploymentOutcome.FAILED);
        var rolledBack = row(pipeline.getId(), b.getId(), "2.9.0", NOW.minusSeconds(100),
                DeploymentOutcome.ROLLED_BACK);
        var good = row(pipeline.getId(), c.getId(), "2.8.0", NOW.minusSeconds(200), null);
        stubMatrixCollaborators(pipeline, List.of(a, b, c), List.of(failed, rolledBack, good));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix.get(2).drift().latestVersion()).isEqualTo("2.8.0");
        assertThat(matrix.get(2).drift().drifted()).isFalse();
        // The failed/rolled-back rows drift against the older-but-successful latest.
        assertThat(matrix.get(0).drift().drifted()).isTrue();
        assertThat(matrix.get(1).drift().drifted()).isTrue();
    }

    @Test
    void nullCurrentVersionAfterConsecutiveRollbacksIsDrifted() {
        var pipeline = pipeline("payments-api");
        var a = environment(pipeline.getId(), "a", 0);
        var b = environment(pipeline.getId(), "b", 1);
        var unknown = row(pipeline.getId(), a.getId(), null, NOW.minusSeconds(86_400),
                DeploymentOutcome.ROLLED_BACK);
        var good = row(pipeline.getId(), b.getId(), "2.4.1", NOW, null);
        stubMatrixCollaborators(pipeline, List.of(a, b), List.of(unknown, good));
        when(requestRepository.findSuccessfulVersionExecutions(pipeline.getId(),
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED))
                .thenReturn(List.of(new DeploymentVersionExecution("2.4.1", NOW)));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        var unknownView = matrix.getFirst();
        assertThat(unknownView.currentVersion()).isNull();
        assertThat(unknownView.drift().drifted()).isTrue();
        assertThat(unknownView.drift().daysBehind()).isEqualTo(1L);
        assertThat(unknownView.drift().deploymentsBehind()).isEqualTo(1L);
    }

    @Test
    void deploymentsBehindExcludesTheRowsOwnVersionAndOlderExecutions() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        var canary = environment(pipeline.getId(), "canary", 1);
        var prodRow = row(pipeline.getId(), prod.getId(), "2.0.0", NOW.minusSeconds(10 * 86_400),
                null);
        var canaryRow = row(pipeline.getId(), canary.getId(), "2.3.0", NOW, null);
        stubMatrixCollaborators(pipeline, List.of(prod, canary), List.of(prodRow, canaryRow));
        when(requestRepository.findSuccessfulVersionExecutions(pipeline.getId(),
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED))
                .thenReturn(List.of(
                        // Older than prod's deploy — not counted.
                        new DeploymentVersionExecution("1.9.0", NOW.minusSeconds(20 * 86_400)),
                        // prod's own version redeployed later elsewhere — excluded.
                        new DeploymentVersionExecution("2.0.0", NOW.minusSeconds(5 * 86_400)),
                        new DeploymentVersionExecution("2.1.0", NOW.minusSeconds(6 * 86_400)),
                        new DeploymentVersionExecution("2.2.0", NOW.minusSeconds(3 * 86_400)),
                        new DeploymentVersionExecution("2.3.0", NOW)));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix.getFirst().drift().deploymentsBehind()).isEqualTo(3L);
        assertThat(matrix.getFirst().drift().daysBehind()).isEqualTo(10L);
    }

    @Test
    void nonNullCurrentWithNoQualifyingLatestIsConservativelyDrifted() {
        // Single-environment pipeline: deploy v2 fails, the tracker reverts current to v1 with
        // lastOutcome=FAILED — no row qualifies as latest, so the last-known-good row is flagged
        // drifted against a null latest rather than declared clean (documented in 04-api-spec).
        var pipeline = pipeline("payments-api");
        var only = environment(pipeline.getId(), "prod", 0);
        var reverted = row(pipeline.getId(), only.getId(), "v1", NOW, DeploymentOutcome.FAILED);
        stubMatrixCollaborators(pipeline, List.of(only), List.of(reverted));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        var drift = matrix.getFirst().drift();
        assertThat(drift.drifted()).isTrue();
        assertThat(drift.latestVersion()).isNull();
        assertThat(drift.latestDeployedAt()).isNull();
        assertThat(drift.daysBehind()).isNull();
        assertThat(drift.deploymentsBehind()).isZero();
    }

    @Test
    void negativeDayGapsClampToZero() {
        var pipeline = pipeline("payments-api");
        var a = environment(pipeline.getId(), "a", 0);
        var b = environment(pipeline.getId(), "b", 1);
        // The drifted row's deploy is NEWER than the latest successful one (its outcome reverted
        // it out of latest candidacy) — the day gap must clamp, not go negative.
        var reverted = row(pipeline.getId(), a.getId(), "2.5.0", NOW, DeploymentOutcome.FAILED);
        var good = row(pipeline.getId(), b.getId(), "2.4.1", NOW.minusSeconds(3 * 86_400), null);
        stubMatrixCollaborators(pipeline, List.of(a, b), List.of(reverted, good));

        var matrix = service.pipelineMatrix(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix.getFirst().drift().drifted()).isTrue();
        assertThat(matrix.getFirst().drift().daysBehind()).isZero();
    }

    // ---------------------------------------------------------------- org-wide list

    @Test
    void listJoinsRowsWithTheirEnvironmentsAndPipelines() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        var prodRow = row(pipeline.getId(), prod.getId(), "2.4.1", NOW, null);
        stubListCollaborators(List.of(prodRow), List.of(prod), List.of(pipeline));

        var page = service.list(filter(null, null, null, null), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        var view = page.content().getFirst();
        assertThat(view.pipelineName()).isEqualTo("payments-api");
        assertThat(view.environmentName()).isEqualTo("prod");
        assertThat(view.currentVersion()).isEqualTo("2.4.1");
        assertThat(view.drift().drifted()).isFalse();
    }

    @Test
    void listIsEmptyForAnOrganizationWithoutDeployments() {
        stubListCollaborators(List.of(), List.of(), List.of());

        var page = service.list(filter(null, null, null, null), PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void listLatestIsComputedOverTheUnfilteredPipelineRowSet() {
        var pipeline = pipeline("payments-api");
        var tagged = environment(pipeline.getId(), "prod-acme", 0);
        var untagged = environment(pipeline.getId(), "prod-globex", 1);
        var taggedRow = row(pipeline.getId(), tagged.getId(), "2.0.0",
                NOW.minusSeconds(2 * 86_400), null);
        var untaggedRow = row(pipeline.getId(), untagged.getId(), "2.4.1", NOW, null);
        // The tag filter's SQL only returned the tagged row…
        stubListCollaborators(List.of(taggedRow), List.of(tagged), List.of(pipeline));
        // …but latest resolution re-reads the pipeline's full row set.
        when(versionRepository.findByPipelineId(pipeline.getId()))
                .thenReturn(List.of(taggedRow, untaggedRow));

        var page = service.list(filter(null, "acme", null, null), PageRequest.of(0, 20));

        var view = page.content().getFirst();
        assertThat(view.drift().latestVersion()).isEqualTo("2.4.1");
        assertThat(view.drift().drifted()).isTrue();
        assertThat(view.drift().daysBehind()).isEqualTo(2L);
    }

    @Test
    void listFiltersByEnvironmentNameCaseInsensitively() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        var staging = environment(pipeline.getId(), "staging", 1);
        var prodRow = row(pipeline.getId(), prod.getId(), "2.4.1", NOW, null);
        var stagingRow = row(pipeline.getId(), staging.getId(), "2.4.1", NOW, null);
        stubListCollaborators(List.of(prodRow, stagingRow), List.of(prod, staging),
                List.of(pipeline));

        var page = service.list(filter(null, null, " PROD ", null), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().getFirst().environmentName()).isEqualTo("prod");
    }

    @Test
    void listFiltersByDriftedBothWays() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        var staging = environment(pipeline.getId(), "staging", 1);
        var behind = row(pipeline.getId(), prod.getId(), "2.0.0", NOW.minusSeconds(86_400), null);
        var current = row(pipeline.getId(), staging.getId(), "2.4.1", NOW, null);
        stubListCollaborators(List.of(behind, current), List.of(prod, staging), List.of(pipeline));

        var driftedPage = service.list(filter(null, null, null, true), PageRequest.of(0, 20));
        var cleanPage = service.list(filter(null, null, null, false), PageRequest.of(0, 20));

        assertThat(driftedPage.totalElements()).isEqualTo(1);
        assertThat(driftedPage.content().getFirst().environmentName()).isEqualTo("prod");
        assertThat(cleanPage.totalElements()).isEqualTo(1);
        assertThat(cleanPage.content().getFirst().environmentName()).isEqualTo("staging");
    }

    @Test
    void listOrdersByPipelineNameThenSortOrderThenEnvironmentName() {
        var alpha = pipeline("alpha");
        var beta = pipeline("Beta");
        var betaEnv = environment(beta.getId(), "prod", 0);
        var alphaSecond = environment(alpha.getId(), "prod", 1);
        var alphaFirstB = environment(alpha.getId(), "b-canary", 0);
        var alphaFirstA = environment(alpha.getId(), "a-canary", 0);
        var rows = List.of(
                row(beta.getId(), betaEnv.getId(), "1.0", NOW, null),
                row(alpha.getId(), alphaSecond.getId(), "1.0", NOW, null),
                row(alpha.getId(), alphaFirstB.getId(), "1.0", NOW, null),
                row(alpha.getId(), alphaFirstA.getId(), "1.0", NOW, null));
        stubListCollaborators(rows, List.of(betaEnv, alphaSecond, alphaFirstB, alphaFirstA),
                List.of(alpha, beta));

        var page = service.list(filter(null, null, null, null), PageRequest.of(0, 20));

        assertThat(page.content()).extracting(v -> v.pipelineName() + "/" + v.environmentName())
                .containsExactly("alpha/a-canary", "alpha/b-canary", "alpha/prod", "Beta/prod");
    }

    @Test
    void listSlicesThePageAndReportsFilteredTotals() {
        var pipeline = pipeline("payments-api");
        var environments = new java.util.ArrayList<DeploymentEnvironmentEntity>();
        var rows = new java.util.ArrayList<DeploymentEnvironmentVersionEntity>();
        for (int i = 0; i < 5; i++) {
            var env = environment(pipeline.getId(), "env-" + i, i);
            environments.add(env);
            rows.add(row(pipeline.getId(), env.getId(), "1.0", NOW, null));
        }
        stubListCollaborators(rows, environments, List.of(pipeline));

        var page = service.list(filter(null, null, null, null), PageRequest.of(1, 2));

        assertThat(page.content()).extracting(v -> v.environmentName())
                .containsExactly("env-2", "env-3");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void listPastTheEndReturnsAnEmptyPageWithCorrectTotals() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        stubListCollaborators(List.of(row(pipeline.getId(), prod.getId(), "1.0", NOW, null)),
                List.of(prod), List.of(pipeline));

        var page = service.list(filter(null, null, null, null), PageRequest.of(7, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void listResolvesDeploymentsBehindOnlyForThePageRows() {
        var behindPipeline = pipeline("a-behind");
        var cleanPipeline = pipeline("b-clean");
        var behindEnv = environment(behindPipeline.getId(), "prod", 0);
        var behindEnv2 = environment(behindPipeline.getId(), "prod2", 1);
        var cleanEnv = environment(cleanPipeline.getId(), "prod", 0);
        var behindRow = row(behindPipeline.getId(), behindEnv.getId(), "1.0",
                NOW.minusSeconds(86_400), null);
        var behindRow2 = row(behindPipeline.getId(), behindEnv2.getId(), "1.0",
                NOW.minusSeconds(86_400), null);
        var latestRow = row(cleanPipeline.getId(), cleanEnv.getId(), "2.0", NOW, null);
        var newestBehind = row(behindPipeline.getId(), UUID.randomUUID(), "2.0", NOW, null);
        stubListCollaborators(List.of(behindRow, behindRow2, latestRow),
                List.of(behindEnv, behindEnv2, cleanEnv), List.of(behindPipeline, cleanPipeline));
        when(versionRepository.findByPipelineId(behindPipeline.getId()))
                .thenReturn(List.of(behindRow, behindRow2, newestBehind));
        when(requestRepository.findSuccessfulVersionExecutions(behindPipeline.getId(),
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED))
                .thenReturn(List.of(new DeploymentVersionExecution("2.0", NOW)));

        // Page 0 of size 2 holds the two drifted rows of the same pipeline: one grouped query.
        var page = service.list(filter(null, null, null, null), PageRequest.of(0, 2));

        assertThat(page.content()).allSatisfy(v ->
                assertThat(v.drift().deploymentsBehind()).isEqualTo(1L));
        verify(requestRepository, times(1)).findSuccessfulVersionExecutions(
                eq(behindPipeline.getId()), eq(QueryStatus.EXECUTED),
                eq(DeploymentOutcome.SUCCEEDED));
        // The clean pipeline's row was sliced away / not drifted — never queried.
        verify(requestRepository, never()).findSuccessfulVersionExecutions(
                eq(cleanPipeline.getId()), any(), any());
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyReturnsThePageNewestFirstWithoutAStatusFilter() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        when(environmentRepository.findById(prod.getId())).thenReturn(Optional.of(prod));
        var request = deploymentRequest(pipeline.getId(), prod.getId());
        when(requestRepository.findByPipelineIdAndEnvironmentIdOrderByCreatedAtDesc(
                eq(pipeline.getId()), eq(prod.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(request)));

        var page = service.history(pipeline.getId(), prod.getId(), null, ORG_ID, CALLER_ID,
                Set.of(Permission.DEPLOYMENT_REVIEW), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        var entry = page.content().getFirst();
        assertThat(entry.requestId()).isEqualTo(request.getId());
        assertThat(entry.version()).isEqualTo("2.4.1");
        assertThat(entry.executedAt()).isEqualTo(request.getExecutedAt());
        assertThat(entry.submittedBy()).isEqualTo(request.getSubmittedBy());
    }

    @Test
    void historyDelegatesToTheStatusFinderWhenAStatusIsGiven() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        when(environmentRepository.findById(prod.getId())).thenReturn(Optional.of(prod));
        when(requestRepository.findByPipelineIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
                eq(pipeline.getId()), eq(prod.getId()), eq(QueryStatus.EXECUTED),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.history(pipeline.getId(), prod.getId(), QueryStatus.EXECUTED, ORG_ID,
                CALLER_ID, Set.of(Permission.DEPLOYMENT_REVIEW), PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(requestRepository, never()).findByPipelineIdAndEnvironmentIdOrderByCreatedAtDesc(
                any(), any(), any(Pageable.class));
    }

    @Test
    void historyIsOpenToATriggerGrantHolder() {
        var pipeline = pipeline("payments-api");
        var prod = environment(pipeline.getId(), "prod", 0);
        when(environmentRepository.findById(prod.getId())).thenReturn(Optional.of(prod));
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, true, false, null)));
        when(requestRepository.findByPipelineIdAndEnvironmentIdOrderByCreatedAtDesc(
                eq(pipeline.getId()), eq(prod.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.history(pipeline.getId(), prod.getId(), null, ORG_ID, CALLER_ID,
                Set.of(), PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    /** Builds a pipeline and registers its org-scoped lookup. */
    private DeploymentPipelineEntity pipeline(String name) {
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(ORG_ID);
        pipeline.setName(name);
        when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), ORG_ID))
                .thenReturn(Optional.of(pipeline));
        return pipeline;
    }

    /** Wires the matrix collaborators for one pipeline; drift executions default to empty. */
    private void stubMatrixCollaborators(DeploymentPipelineEntity pipeline,
                                         List<DeploymentEnvironmentEntity> environments,
                                         List<DeploymentEnvironmentVersionEntity> rows) {
        when(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipeline.getId()))
                .thenReturn(environments);
        when(versionRepository.findByPipelineId(pipeline.getId())).thenReturn(rows);
        when(requestRepository.findSuccessfulVersionExecutions(pipeline.getId(),
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED)).thenReturn(List.of());
    }

    /** Wires the org-wide list collaborators: the spec result, the joined lookups, and latest. */
    @SuppressWarnings("unchecked")
    private void stubListCollaborators(List<DeploymentEnvironmentVersionEntity> rows,
                                       List<DeploymentEnvironmentEntity> environments,
                                       List<DeploymentPipelineEntity> pipelines) {
        when(versionRepository.findAll(any(Specification.class))).thenReturn(rows);
        when(environmentRepository.findAllById(any())).thenReturn(environments);
        when(pipelineRepository.findAllById(any())).thenReturn(pipelines);
        for (var pipeline : pipelines) {
            var pipelineRows = rows.stream()
                    .filter(r -> r.getPipelineId().equals(pipeline.getId())).toList();
            when(versionRepository.findByPipelineId(pipeline.getId())).thenReturn(pipelineRows);
            when(requestRepository.findSuccessfulVersionExecutions(pipeline.getId(),
                    QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED)).thenReturn(List.of());
        }
    }

    private static DeploymentEnvironmentEntity environment(UUID pipelineId, String name,
                                                           int sortOrder) {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        environment.setPipelineId(pipelineId);
        environment.setName(name);
        environment.setSortOrder(sortOrder);
        return environment;
    }

    private static DeploymentEnvironmentVersionEntity row(UUID pipelineId, UUID environmentId,
                                                          String currentVersion, Instant deployedAt,
                                                          DeploymentOutcome lastOutcome) {
        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(ORG_ID);
        row.setPipelineId(pipelineId);
        row.setEnvironmentId(environmentId);
        row.setCurrentVersion(currentVersion);
        row.setCurrentRequestId(UUID.randomUUID());
        row.setDeployedAt(deployedAt);
        row.setLastOutcome(lastOutcome);
        return row;
    }

    private static DeploymentRequestEntity deploymentRequest(UUID pipelineId, UUID environmentId) {
        var request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setOrganizationId(ORG_ID);
        request.setPipelineId(pipelineId);
        request.setEnvironmentId(environmentId);
        request.setSubmittedBy(UUID.randomUUID());
        request.setVersion("2.4.1");
        request.setStatus(QueryStatus.EXECUTED);
        request.setExecutedAt(NOW);
        return request;
    }

    private static DeploymentEnvironmentVersionListFilter filter(UUID pipelineId, String tag,
                                                                 String environment,
                                                                 Boolean drifted) {
        return new DeploymentEnvironmentVersionListFilter(ORG_ID, pipelineId, tag, environment,
                drifted);
    }
}
