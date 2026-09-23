package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.ActiveDeploymentFreezeView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderBlocker;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungState;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaChangeLadderServiceTest {

    @Mock
    private SchemaChangeSetRepository changeSetRepository;
    @Mock
    private SchemaChangeSetPromotionRepository promotionRepository;
    @Mock
    private DeploymentPipelineAdminService pipelineAdminService;
    @Mock
    private DeploymentEnvironmentLookupService environmentLookupService;
    @Mock
    private DeploymentFreezeLookupService freezeLookupService;
    @Mock
    private DatasourceAdminService datasourceAdminService;

    private DefaultSchemaChangeLadderService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID devId = UUID.randomUUID();
    private final UUID stagingId = UUID.randomUUID();
    private final UUID prodId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaChangeLadderService(changeSetRepository, promotionRepository,
                pipelineAdminService, environmentLookupService, freezeLookupService, datasourceAdminService);
    }

    @Test
    void listPipelinesWalksEveryPageAndMapsEnvironments() {
        var second = UUID.randomUUID();
        when(pipelineAdminService.list(organizationId, PageRequest.of(0, 100)))
                .thenReturn(new PageResponse<>(List.of(pipeline(pipelineId, "orders")), 0, 100, 2, 2));
        when(pipelineAdminService.list(organizationId, PageRequest.of(1, 100)))
                .thenReturn(new PageResponse<>(List.of(pipeline(second, "billing")), 1, 100, 2, 2));
        when(environmentLookupService.listByPipeline(pipelineId))
                .thenReturn(List.of(env(devId, "dev", 0, UUID.randomUUID())));
        when(environmentLookupService.listByPipeline(second)).thenReturn(List.of());

        var pipelines = service.listPipelines(organizationId);

        assertThat(pipelines).extracting("name").containsExactly("orders", "billing");
        assertThat(pipelines.getFirst().environments()).singleElement()
                .extracting("id", "name", "sortOrder").containsExactly(devId, "dev", 0);
    }

    @Test
    void listPipelinesStopsOnAnEmptyOrganization() {
        when(pipelineAdminService.list(organizationId, PageRequest.of(0, 100)))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        assertThat(service.listPipelines(organizationId)).isEmpty();
    }

    @Test
    void ladderRefusesAForeignChangeSet() {
        when(changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ladder(organizationId, changeSetId))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
    }

    @Test
    void entryRungIsPromotableAndHigherRungsNameTheUnappliedLowerRung() {
        givenChangeSet(SchemaChangeSetStatus.DRAFT, "c".repeat(64));
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()), env(stagingId, "staging", 1, UUID.randomUUID()),
                env(prodId, "prod", 2, UUID.randomUUID()));
        givenPromotions();

        var rungs = service.ladder(organizationId, changeSetId).rungs();

        assertThat(rungs.get(0).state()).isEqualTo(SchemaChangeLadderRungState.PROMOTABLE);
        assertThat(rungs.get(1).blocker()).isEqualTo(SchemaChangeLadderBlocker.LOWER_ENVIRONMENT_NOT_APPLIED);
        assertThat(rungs.get(1).blockingEnvironmentName()).isEqualTo("dev");
        assertThat(rungs.get(2).blockingEnvironmentId()).isEqualTo(devId);
    }

    @Test
    void appliedAndOpenPromotionsDriveTheRungStateAndUnboundRungsAreSkippedByTheGate() {
        givenChangeSet(SchemaChangeSetStatus.ACTIVE, "c".repeat(64));
        var unbound = UUID.randomUUID();
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()), env(unbound, "deploy-only", 1, null),
                env(stagingId, "staging", 2, UUID.randomUUID()), env(prodId, "prod", 3, UUID.randomUUID()));
        givenPromotions(promotion(stagingId, SchemaChangePromotionStatus.IN_REVIEW),
                promotion(devId, SchemaChangePromotionStatus.FAILED),
                promotion(devId, SchemaChangePromotionStatus.APPLIED));

        var rungs = service.ladder(organizationId, changeSetId).rungs();

        assertThat(rungs.get(0).state()).isEqualTo(SchemaChangeLadderRungState.APPLIED);
        assertThat(rungs.get(0).latestPromotion().status()).isEqualTo(SchemaChangePromotionStatus.FAILED);
        assertThat(rungs.get(0).latestPromotion().schemaSnapshot()).isNull();
        assertThat(rungs.get(1).blocker()).isEqualTo(SchemaChangeLadderBlocker.NO_DATASOURCE);
        assertThat(rungs.get(2).state()).isEqualTo(SchemaChangeLadderRungState.IN_PROGRESS);
        assertThat(rungs.get(3).blockingEnvironmentName()).isEqualTo("staging");
    }

    @Test
    void freezeWindowBlocksAnOtherwisePromotableRung() {
        givenChangeSet(SchemaChangeSetStatus.DRAFT, "c".repeat(64));
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()));
        givenPromotions();
        var windowId = UUID.randomUUID();
        when(freezeLookupService.evaluate(organizationId, pipelineId, devId))
                .thenReturn(Optional.of(new ActiveDeploymentFreezeView(windowId, FreezeBehavior.HOLD, "quarter end")));

        var rung = service.ladder(organizationId, changeSetId).rungs().getFirst();

        assertThat(rung).extracting(SchemaChangeLadderRungView::state, SchemaChangeLadderRungView::blocker,
                        SchemaChangeLadderRungView::freezeWindowId, SchemaChangeLadderRungView::freezeBehavior,
                        SchemaChangeLadderRungView::freezeReason)
                .containsExactly(SchemaChangeLadderRungState.BLOCKED, SchemaChangeLadderBlocker.FREEZE_ACTIVE,
                        windowId, FreezeBehavior.HOLD, "quarter end");
    }

    @Test
    void anArchivedSetIsReportedBeforeAnyRungCheckLikeTheGate() {
        givenChangeSet(SchemaChangeSetStatus.ARCHIVED, "c".repeat(64));
        givenLadder(env(devId, "dev", 0, null), env(stagingId, "staging", 1, UUID.randomUUID()));
        givenPromotions();

        assertThat(service.ladder(organizationId, changeSetId).rungs())
                .extracting(SchemaChangeLadderRungView::blocker)
                .containsOnly(SchemaChangeLadderBlocker.SET_ARCHIVED);
        verify(freezeLookupService, never()).evaluate(any(), any(), any());
    }

    @Test
    void aPartialApplyIsAnAdvisoryBlockUntilANewerAttemptSupersedesIt() {
        givenChangeSet(SchemaChangeSetStatus.ACTIVE, "c".repeat(64));
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()));
        givenPromotions(promotion(devId, SchemaChangePromotionStatus.PARTIALLY_APPLIED));

        assertThat(service.ladder(organizationId, changeSetId).rungs().getFirst().blocker())
                .isEqualTo(SchemaChangeLadderBlocker.PARTIALLY_APPLIED);
    }

    @Test
    void aDeletedBoundDatasourceBlocksLikeGateCheckSixAndIsLookedUpOnce() {
        givenChangeSet(SchemaChangeSetStatus.DRAFT, "c".repeat(64));
        var deleted = UUID.randomUUID();
        givenLadder(env(devId, "dev", 0, deleted), env(stagingId, "staging", 1, deleted));
        givenPromotions();
        when(datasourceAdminService.getForAdmin(deleted, organizationId))
                .thenThrow(new DatasourceNotFoundException(deleted));

        assertThat(service.ladder(organizationId, changeSetId).rungs())
                .extracting(SchemaChangeLadderRungView::blocker)
                .containsOnly(SchemaChangeLadderBlocker.DATASOURCE_MISSING);
        verify(datasourceAdminService, org.mockito.Mockito.times(1)).getForAdmin(deleted, organizationId);
    }

    @Test
    void emptySetBlocksEveryBoundRung() {
        givenChangeSet(SchemaChangeSetStatus.DRAFT, null);
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()));
        givenPromotions();

        assertThat(service.ladder(organizationId, changeSetId).rungs().getFirst().blocker())
                .isEqualTo(SchemaChangeLadderBlocker.SET_EMPTY);
    }

    @Test
    void duplicateSortOrdersNeverPassVacuously() {
        givenChangeSet(SchemaChangeSetStatus.DRAFT, "c".repeat(64));
        givenLadder(env(devId, "dev", 0, UUID.randomUUID()), env(stagingId, "staging", 0, UUID.randomUUID()));
        givenPromotions();

        assertThat(service.ladder(organizationId, changeSetId).rungs())
                .extracting(SchemaChangeLadderRungView::blocker)
                .containsOnly(SchemaChangeLadderBlocker.LADDER_INVALID);
    }

    private void givenChangeSet(SchemaChangeSetStatus status, String checksum) {
        var entity = changeSetEntity(status, checksum);
        when(changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId)).thenReturn(Optional.of(entity));
    }

    private SchemaChangeSetEntity changeSetEntity(SchemaChangeSetStatus status, String checksum) {
        var entity = new SchemaChangeSetEntity();
        entity.setId(changeSetId);
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(pipelineId);
        entity.setName("orders-v2");
        entity.setStatus(status);
        entity.setStatementsChecksum(checksum);
        return entity;
    }

    private void givenLadder(DeploymentEnvironmentView... environments) {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(environments));
        lenient().when(freezeLookupService.evaluate(any(), any(), any())).thenReturn(Optional.empty());
    }

    private void givenPromotions(SchemaChangeSetPromotionEntity... promotions) {
        when(promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(changeSetId))
                .thenReturn(new ArrayList<>(List.of(promotions)));
    }

    private SchemaChangeSetPromotionEntity promotion(UUID environmentId, SchemaChangePromotionStatus status) {
        var entity = new SchemaChangeSetPromotionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setChangeSet(changeSetEntity(SchemaChangeSetStatus.ACTIVE, "c".repeat(64)));
        entity.setEnvironmentId(environmentId);
        entity.setDatasourceId(UUID.randomUUID());
        entity.setStatus(status);
        entity.setStatementsChecksum("c".repeat(64));
        entity.setPromotedBy(UUID.randomUUID());
        entity.setSubmittedAt(now);
        entity.setSchemaSnapshot("{}");
        return entity;
    }

    private DeploymentEnvironmentView env(UUID id, String name, int sortOrder, UUID datasourceId) {
        return new DeploymentEnvironmentView(id, pipelineId, name, sortOrder, false, null, null, false, Instant.EPOCH,
                List.of(), datasourceId);
    }

    private DeploymentPipelineView pipeline(UUID id, String name) {
        return new DeploymentPipelineView(id, organizationId, name, PipelineProvider.GITHUB_ACTIONS, null, null, null,
                true, null, true, null, null);
    }
}
