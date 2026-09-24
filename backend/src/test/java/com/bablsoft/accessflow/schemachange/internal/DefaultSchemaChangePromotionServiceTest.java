package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.deploygov.api.ActiveDeploymentFreezeView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.schemachange.api.PromoteSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNoDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionDdlForbiddenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotCancellableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionReviewUnenforceableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetEmptyException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetTargetDatasourceMissingException;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaChangePromotionServiceTest {

    private static final String CREATE = "CREATE TABLE t (id INT)";
    private static final String ALTER = "ALTER TABLE t ADD c INT";

    @Mock
    private SchemaChangeSetRepository changeSetRepository;
    @Mock
    private SchemaChangeSetStatementRepository statementRepository;
    @Mock
    private SchemaChangeSetPromotionRepository promotionRepository;
    @Mock
    private DeploymentPipelineLookupService pipelineLookupService;
    @Mock
    private DeploymentEnvironmentLookupService environmentLookupService;
    @Mock
    private DeploymentFreezeLookupService freezeLookupService;
    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private DatasourceUserPermissionLookupService permissionLookupService;
    @Mock
    private ReviewPlanLookupService reviewPlanLookupService;
    @Mock
    private RequestGroupService requestGroupService;
    @Mock
    private com.bablsoft.accessflow.audit.api.AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID otherOrganizationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID devDatasourceId = UUID.randomUUID();
    private final UUID prodDatasourceId = UUID.randomUUID();
    private final UUID devId = UUID.randomUUID();
    private final UUID stagingId = UUID.randomUUID();
    private final UUID prodId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-22T10:00:00Z");

    private DefaultSchemaChangePromotionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaChangePromotionService(changeSetRepository, statementRepository,
                promotionRepository, pipelineLookupService, environmentLookupService, freezeLookupService,
                datasourceAdminService, permissionLookupService, reviewPlanLookupService, requestGroupService,
                new SchemaChangeAuditWriter(auditLogService), eventPublisher,
                Clock.fixed(now, ZoneOffset.UTC));
        lenient().when(promotionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(changeSetRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(requestGroupService.createDraft(any())).thenReturn(groupView());
    }

    // ---- happy path ----

    @Test
    void promotesTheLowestRungAndSubmitsAnOrderedGroupForTheScheduledRun() {
        givenPromotable();

        var view = service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId, "10.0.0.7", "curl/8"));

        assertThat(view.status()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        assertThat(view.environmentId()).isEqualTo(devId);
        assertThat(view.environmentName()).isEqualTo("dev");
        assertThat(view.datasourceId()).isEqualTo(devDatasourceId);
        assertThat(view.requestGroupId()).isEqualTo(groupId);
        assertThat(view.promotedBy()).isEqualTo(actorId);
        assertThat(view.submittedAt()).isEqualTo(now);
        assertThat(view.statementsChecksum()).isEqualTo(checksum());

        var create = ArgumentCaptor.forClass(CreateRequestGroupCommand.class);
        verify(requestGroupService).createDraft(create.capture());
        assertThat(create.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(create.getValue().submitterUserId()).isEqualTo(actorId);
        assertThat(create.getValue().continueOnError()).isFalse();
        assertThat(create.getValue().items()).extracting("targetKind", "datasourceId", "sqlText", "transactional")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RequestGroupTargetKind.QUERY, devDatasourceId, CREATE, false),
                        org.assertj.core.groups.Tuple.tuple(RequestGroupTargetKind.QUERY, devDatasourceId, ALTER, false));

        var submit = ArgumentCaptor.forClass(SubmitRequestGroupCommand.class);
        verify(requestGroupService).submit(submit.capture());
        assertThat(submit.getValue().requestGroupId()).isEqualTo(groupId);
        assertThat(submit.getValue().breakGlass()).isFalse();
        // The durable execution trigger: ScheduledGroupRunJob only picks up a group with scheduled_for set.
        assertThat(submit.getValue().scheduledFor()).isEqualTo(now);
        assertThat(submit.getValue().submittedIp()).isEqualTo("10.0.0.7");
        assertThat(submit.getValue().submittedUserAgent()).isEqualTo("curl/8");
    }

    /** requestgroups exempts QUERY_ADMIN from its own per-member check, so schemachange owns the decision. */
    @Test
    void delegatesToRequestGroupsAsAdminOnlyAfterEnforcingDdlItself() {
        givenPromotable();

        service.promote(organizationId, actorId, changeSetId, new PromoteSchemaChangeSetCommand(devId));

        var create = ArgumentCaptor.forClass(CreateRequestGroupCommand.class);
        verify(requestGroupService).createDraft(create.capture());
        assertThat(create.getValue().admin()).isTrue();
        var submit = ArgumentCaptor.forClass(SubmitRequestGroupCommand.class);
        verify(requestGroupService).submit(submit.capture());
        assertThat(submit.getValue().admin()).isTrue();
        verify(permissionLookupService).findFor(actorId, devDatasourceId);
    }

    @Test
    void flipsADraftSetToActiveAndLeavesAnAlreadyActiveOneAlone() {
        var set = givenPromotable();

        service.promote(organizationId, actorId, changeSetId, new PromoteSchemaChangeSetCommand(devId));
        assertThat(set.getStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);

        service.promote(organizationId, actorId, changeSetId, new PromoteSchemaChangeSetCommand(devId));
        assertThat(set.getStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        verify(changeSetRepository).saveAndFlush(set);
    }

    @Test
    void auditsTheSubmissionAndPublishesTheInitialTransition() {
        givenPromotable();

        var view = service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId, "10.0.0.7", "curl/8"));

        var entry = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.SCHEMA_CHANGE_PROMOTION_SUBMITTED);
        assertThat(entry.getValue().actorId()).isEqualTo(actorId);
        assertThat(entry.getValue().resourceId()).isEqualTo(view.id());
        assertThat(entry.getValue().metadata())
                .containsEntry("environment_name", "dev")
                .containsEntry("statement_count", 2)
                .containsEntry("request_group_id", groupId.toString())
                .containsEntry("statements_checksum", checksum());

        var event = ArgumentCaptor.forClass(SchemaChangePromotionStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().oldStatus()).isNull();
        assertThat(event.getValue().newStatus()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        assertThat(event.getValue().environmentId()).isEqualTo(devId);
    }

    // ---- the ladder gate ----

    @Test
    void refusesAHigherRungWhileALowerOneIsUnapplied() {
        givenPromotable(prodId);

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(prodId)))
                .isInstanceOf(SchemaChangePromotionLadderBlockedException.class)
                .satisfies(ex -> assertThat(((SchemaChangePromotionLadderBlockedException) ex)
                        .blockingEnvironmentName()).isEqualTo("dev"));
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void enforcesTheLadderRungByRungRatherThanOnlyTheEntryRung() {
        givenPromotable(prodId);
        when(promotionRepository.existsByChangeSet_IdAndEnvironmentIdAndStatus(
                changeSetId, devId, SchemaChangePromotionStatus.APPLIED)).thenReturn(true);

        // dev applied, staging still missing: the gate must name staging, not pass.
        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(prodId)))
                .isInstanceOf(SchemaChangePromotionLadderBlockedException.class)
                .satisfies(ex -> assertThat(((SchemaChangePromotionLadderBlockedException) ex)
                        .blockingEnvironmentName()).isEqualTo("staging"));

        when(promotionRepository.existsByChangeSet_IdAndEnvironmentIdAndStatus(
                changeSetId, stagingId, SchemaChangePromotionStatus.APPLIED)).thenReturn(true);

        assertThat(service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(prodId)).environmentId()).isEqualTo(prodId);
    }

    @Test
    void ignoresLowerRungsThatBindNoDatasource() {
        var set = changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        var unbound = environment(devId, "dev", 0, null, false);
        var target = environment(prodId, "prod", 1, prodDatasourceId, false);
        when(pipelineLookupService.findEnvironment(pipelineId, prodId)).thenReturn(Optional.of(target));
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(unbound, target));
        givenDatasource(prodDatasourceId);
        givenDdl(prodDatasourceId, true);

        assertThat(service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(prodId)).environmentId()).isEqualTo(prodId);
        assertThat(set.getStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        verify(promotionRepository, never()).existsByChangeSet_IdAndEnvironmentIdAndStatus(
                changeSetId, devId, SchemaChangePromotionStatus.APPLIED);
    }

    /** A gate over an all-equal sort_order column would pass vacuously — refuse instead. */
    @Test
    void refusesALadderWhoseSortOrdersAreNotDistinct() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        var dev = environment(devId, "dev", 0, devDatasourceId, false);
        var prod = environment(prodId, "prod", 0, prodDatasourceId, false);
        when(pipelineLookupService.findEnvironment(pipelineId, prodId)).thenReturn(Optional.of(prod));
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(dev, prod));
        givenDatasource(prodDatasourceId);
        givenDdl(prodDatasourceId, true);

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(prodId)))
                .isInstanceOf(SchemaChangePromotionLadderInvalidException.class);
        verifyNoInteractions(requestGroupService);
    }

    // ---- the remaining gates ----

    @Test
    void refusesAChangeSetFromAnotherOrganization() {
        when(changeSetRepository.findByIdAndOrganizationId(changeSetId, otherOrganizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promote(otherOrganizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
    }

    @Test
    void refusesAnArchivedChangeSet() {
        changeSet(SchemaChangeSetStatus.ARCHIVED);

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeSetArchivedException.class);
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void refusesAChangeSetWithoutStatements() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        when(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(changeSetId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeSetEmptyException.class);
    }

    @Test
    void refusesAnEnvironmentThatIsNotOnTheChangeSetsPipeline() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        when(pipelineLookupService.findEnvironment(pipelineId, devId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeEnvironmentNotFoundException.class);
    }

    @Test
    void refusesADeployOnlyEnvironment() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        when(pipelineLookupService.findEnvironment(pipelineId, devId))
                .thenReturn(Optional.of(environment(devId, "dev", 0, null, false)));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeEnvironmentNoDatasourceException.class);
    }

    @Test
    void refusesWhenTheBoundDatasourceNoLongerExists() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        when(pipelineLookupService.findEnvironment(pipelineId, devId))
                .thenReturn(Optional.of(environment(devId, "dev", 0, devDatasourceId, false)));
        when(datasourceAdminService.getForAdmin(devDatasourceId, organizationId))
                .thenThrow(new DatasourceNotFoundException(devDatasourceId));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangeSetTargetDatasourceMissingException.class);
    }

    @Test
    void refusesAPromoterWithoutDdlBeforeCreatingAnything() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        when(pipelineLookupService.findEnvironment(pipelineId, devId))
                .thenReturn(Optional.of(environment(devId, "dev", 0, devDatasourceId, false)));
        givenDatasource(devDatasourceId);
        givenDdl(devDatasourceId, false);

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionDdlForbiddenException.class);
        verifyNoInteractions(requestGroupService);
        verify(promotionRepository, never()).saveAndFlush(any());
    }

    /** No admin exemption: an org admin without the grant is refused exactly like anyone else. */
    @Test
    void refusesAnOrgAdminWithoutDdl() {
        changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        when(pipelineLookupService.findEnvironment(pipelineId, devId))
                .thenReturn(Optional.of(environment(devId, "dev", 0, devDatasourceId, false)));
        givenDatasource(devDatasourceId);
        when(permissionLookupService.findFor(actorId, devDatasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionDdlForbiddenException.class);
        verifyNoInteractions(requestGroupService);
    }

    @ParameterizedTest
    @EnumSource(FreezeBehavior.class)
    void refusesAnyActiveFreezeWindow(FreezeBehavior behavior) {
        givenPromotable();
        when(freezeLookupService.evaluate(organizationId, pipelineId, devId))
                .thenReturn(Optional.of(new ActiveDeploymentFreezeView(UUID.randomUUID(), behavior, "year end")));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionFrozenException.class)
                .satisfies(ex -> assertThat(((SchemaChangePromotionFrozenException) ex).behavior())
                        .isEqualTo(behavior));
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void refusesAReviewRequiringEnvironmentWhoseDatasourceCannotEnforceIt() {
        givenPromotable(devId, true);
        when(reviewPlanLookupService.findForDatasource(devDatasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionReviewUnenforceableException.class);

        when(reviewPlanLookupService.findForDatasource(devDatasourceId)).thenReturn(Optional.of(plan(false)));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionReviewUnenforceableException.class);
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void acceptsAReviewRequiringEnvironmentBackedByAHumanApprovalPlan() {
        givenPromotable(devId, true);
        when(reviewPlanLookupService.findForDatasource(devDatasourceId)).thenReturn(Optional.of(plan(true)));

        assertThat(service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)).environmentId()).isEqualTo(devId);
    }

    @Test
    void refusesASecondOpenPromotionToTheSameEnvironment() {
        givenPromotable();
        when(promotionRepository.existsByChangeSet_IdAndEnvironmentIdAndStatusIn(
                eq(changeSetId), eq(devId), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionConflictException.class);
        verifyNoInteractions(requestGroupService);
    }

    /** The pre-check loses a race; the partial unique index does not, and it must read as the same 409. */
    @Test
    void translatesTheRacedUniqueViolationToTheSameConflict() {
        givenPromotable();
        when(promotionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("insert",
                new SQLException("duplicate key value violates unique constraint \""
                        + DefaultSchemaChangePromotionService.OPEN_CONSTRAINT + "\"")));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(SchemaChangePromotionConflictException.class);
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void rethrowsAnUnrelatedIntegrityViolation() {
        givenPromotable();
        when(promotionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("insert",
                new SQLException("null value in column \"datasource_id\"")));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Stored text and stored checksum disagreeing is corruption, not a caller error. */
    @Test
    void refusesToPromoteWhenTheStoredChecksumDoesNotMatchTheStatements() {
        var set = givenPromotable();
        set.setStatementsChecksum("b".repeat(64));

        assertThatThrownBy(() -> service.promote(organizationId, actorId, changeSetId,
                new PromoteSchemaChangeSetCommand(devId)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(requestGroupService);
    }

    // ---- reads ----

    @Test
    void getResolvesTheEnvironmentNameAndScopesToTheOrganization() {
        var promotion = promotion(SchemaChangePromotionStatus.APPLIED);
        when(promotionRepository.findByIdAndOrganizationId(promotion.getId(), organizationId))
                .thenReturn(Optional.of(promotion));
        when(environmentLookupService.findById(devId))
                .thenReturn(Optional.of(environment(devId, "dev", 0, devDatasourceId, false)));

        assertThat(service.get(organizationId, promotion.getId()).environmentName()).isEqualTo("dev");

        when(promotionRepository.findByIdAndOrganizationId(promotion.getId(), otherOrganizationId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(otherOrganizationId, promotion.getId()))
                .isInstanceOf(SchemaChangePromotionNotFoundException.class);
    }

    @Test
    void getLeavesTheEnvironmentNameNullOnceTheEnvironmentIsGone() {
        var promotion = promotion(SchemaChangePromotionStatus.APPLIED);
        when(promotionRepository.findByIdAndOrganizationId(promotion.getId(), organizationId))
                .thenReturn(Optional.of(promotion));
        when(environmentLookupService.findById(devId)).thenReturn(Optional.empty());

        assertThat(service.get(organizationId, promotion.getId()).environmentName()).isNull();
    }

    /** One ladder lookup for every row, and no megabyte-sized snapshot on a list the UI polls. */
    @Test
    void listForChangeSetNamesEnvironmentsInOneLookupAndOmitsSnapshots() {
        changeSet(SchemaChangeSetStatus.ACTIVE);
        var promotion = promotion(SchemaChangePromotionStatus.APPLIED);
        promotion.setSchemaSnapshot("{\"schemas\":[]}");
        promotion.setSnapshotTakenAt(now);
        when(promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(changeSetId))
                .thenReturn(List.of(promotion));
        when(environmentLookupService.listByPipeline(pipelineId))
                .thenReturn(List.of(environment(devId, "dev", 0, devDatasourceId, false)));

        assertThat(service.listForChangeSet(organizationId, changeSetId)).singleElement()
                .extracting("environmentName", "schemaSnapshot", "snapshotTakenAt")
                .containsExactly("dev", null, now);
        verify(environmentLookupService, never()).findById(any());
    }

    @Test
    void listForChangeSetRequiresTheSetInTheOrganizationFirst() {
        when(changeSetRepository.findByIdAndOrganizationId(changeSetId, otherOrganizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForChangeSet(otherOrganizationId, changeSetId))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
    }

    // ---- cancel ----

    /** Cancel reads under the same row lock the projection listener takes — see the repository javadoc. */
    @Test
    void cancelsThroughTheGroupUnderTheRowLockAndAuditsTheRealActor() {
        var canceller = UUID.randomUUID();
        var promotion = promotion(SchemaChangePromotionStatus.IN_REVIEW);
        when(promotionRepository.findByIdAndOrganizationIdForUpdate(promotion.getId(), organizationId))
                .thenReturn(Optional.of(promotion));

        service.cancel(organizationId, canceller, promotion.getId());

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.CANCELLED);
        // The group's own cancel is submitter-only, so it runs as the promoter.
        verify(requestGroupService).cancel(groupId, organizationId, actorId);
        var entry = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.SCHEMA_CHANGE_PROMOTION_CANCELLED);
        assertThat(entry.getValue().actorId()).isEqualTo(canceller);
        assertThat(entry.getValue().metadata())
                .containsEntry("request_group_id", groupId.toString())
                .containsEntry("cancelled_on_behalf_of_submitter", true);
        var event = ArgumentCaptor.forClass(SchemaChangePromotionStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().oldStatus()).isEqualTo(SchemaChangePromotionStatus.IN_REVIEW);
        assertThat(event.getValue().newStatus()).isEqualTo(SchemaChangePromotionStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class,
            names = {"APPLIED", "FAILED", "PARTIALLY_APPLIED", "CANCELLED"})
    void refusesToCancelATerminalPromotion(SchemaChangePromotionStatus terminal) {
        var promotion = promotion(terminal);
        when(promotionRepository.findByIdAndOrganizationIdForUpdate(promotion.getId(), organizationId))
                .thenReturn(Optional.of(promotion));

        assertThatThrownBy(() -> service.cancel(organizationId, actorId, promotion.getId()))
                .isInstanceOf(SchemaChangePromotionNotCancellableException.class);
        verifyNoInteractions(requestGroupService);
    }

    @Test
    void translatesAGroupThatCanNoLongerBeCancelled() {
        var promotion = promotion(SchemaChangePromotionStatus.APPROVED);
        when(promotionRepository.findByIdAndOrganizationIdForUpdate(promotion.getId(), organizationId))
                .thenReturn(Optional.of(promotion));
        org.mockito.Mockito.doThrow(new IllegalRequestGroupStateException(RequestGroupStatus.EXECUTING, "running"))
                .when(requestGroupService).cancel(groupId, organizationId, actorId);

        assertThatThrownBy(() -> service.cancel(organizationId, actorId, promotion.getId()))
                .isInstanceOf(SchemaChangePromotionNotCancellableException.class);
        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPROVED);
    }

    @Test
    void refusesToCancelAPromotionOfAnotherOrganization() {
        var promotionId = UUID.randomUUID();
        when(promotionRepository.findByIdAndOrganizationIdForUpdate(promotionId, otherOrganizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(otherOrganizationId, actorId, promotionId))
                .isInstanceOf(SchemaChangePromotionNotFoundException.class);
    }

    // ---- helpers ----

    private SchemaChangeSetEntity givenPromotable() {
        return givenPromotable(devId, false);
    }

    private SchemaChangeSetEntity givenPromotable(UUID targetId) {
        return givenPromotable(targetId, false);
    }

    /** Three distinct rungs, all bound; the target resolves and the promoter holds can_ddl. */
    private SchemaChangeSetEntity givenPromotable(UUID targetId, boolean requireReview) {
        var set = changeSet(SchemaChangeSetStatus.DRAFT);
        givenStatements();
        var dev = environment(devId, "dev", 0, devDatasourceId, targetId.equals(devId) && requireReview);
        var staging = environment(stagingId, "staging", 1, UUID.randomUUID(), false);
        var prod = environment(prodId, "prod", 2, prodDatasourceId, targetId.equals(prodId) && requireReview);
        var target = targetId.equals(devId) ? dev : targetId.equals(stagingId) ? staging : prod;
        when(pipelineLookupService.findEnvironment(pipelineId, targetId)).thenReturn(Optional.of(target));
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(dev, staging, prod));
        var datasourceId = target.datasourceId();
        givenDatasource(datasourceId);
        givenDdl(datasourceId, true);
        return set;
    }

    /** A detached parent for promotion rows — {@link #changeSet} also stubs, which cannot nest. */
    private SchemaChangeSetEntity plainChangeSet() {
        var entity = new SchemaChangeSetEntity();
        entity.setId(changeSetId);
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(pipelineId);
        entity.setName("orders-v2");
        entity.setStatus(SchemaChangeSetStatus.ACTIVE);
        entity.setStatementsChecksum(checksum());
        return entity;
    }

    private SchemaChangeSetEntity changeSet(SchemaChangeSetStatus status) {
        var entity = new SchemaChangeSetEntity();
        entity.setId(changeSetId);
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(pipelineId);
        entity.setName("orders-v2");
        entity.setStatus(status);
        entity.setStatementsChecksum(checksum());
        lenient().when(changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId))
                .thenReturn(Optional.of(entity));
        return entity;
    }

    private void givenStatements() {
        when(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(changeSetId))
                .thenReturn(List.of(statement(0, CREATE), statement(1, ALTER)));
    }

    private SchemaChangeSetStatementEntity statement(int order, String sql) {
        var row = new SchemaChangeSetStatementEntity();
        row.setId(UUID.randomUUID());
        row.setSequenceOrder(order);
        row.setSqlText(sql);
        row.setQueryType(QueryType.DDL);
        return row;
    }

    private void givenDatasource(UUID datasourceId) {
        lenient().when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(mock(com.bablsoft.accessflow.core.api.DatasourceView.class));
    }

    private void givenDdl(UUID datasourceId, boolean canDdl) {
        lenient().when(permissionLookupService.findFor(actorId, datasourceId)).thenReturn(Optional.of(
                new DatasourceUserPermissionView(UUID.randomUUID(), actorId, datasourceId, true, true, canDdl, false,
                        List.of(), List.of(), List.of(), null, null, null)));
    }

    private DeploymentEnvironmentView environment(UUID id, String name, int sortOrder, UUID datasourceId,
                                                  boolean requireReview) {
        return new DeploymentEnvironmentView(id, pipelineId, name, sortOrder, requireReview, null, null, false,
                Instant.EPOCH, List.of(), datasourceId);
    }

    private ReviewPlanSnapshot plan(boolean requiresHumanApproval) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, false, requiresHumanApproval, 1, false, 1,
                List.of(), List.of(), null, null);
    }

    private SchemaChangeSetPromotionEntity promotion(SchemaChangePromotionStatus status) {
        var entity = new SchemaChangeSetPromotionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setChangeSet(plainChangeSet());
        entity.setEnvironmentId(devId);
        entity.setDatasourceId(devDatasourceId);
        entity.setRequestGroupId(groupId);
        entity.setStatus(status);
        entity.setStatementsChecksum(checksum());
        entity.setPromotedBy(actorId);
        entity.setSubmittedAt(now);
        return entity;
    }

    private RequestGroupView groupView() {
        return new RequestGroupView(groupId, organizationId, actorId, "Admin", "schema-change", null,
                RequestGroupStatus.DRAFT, false, null, null, null, 1, 1, null, null, null, now, now, List.of(), null);
    }

    private static String checksum() {
        return SchemaChangeChecksum.of(List.of(CREATE, ALTER));
    }
}
