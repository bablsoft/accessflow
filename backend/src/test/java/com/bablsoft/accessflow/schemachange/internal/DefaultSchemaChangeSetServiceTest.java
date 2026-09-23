package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.schemachange.api.CreateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNameConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementLimitException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatusTransitionException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.schemachange.api.UpdateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.internal.SchemaChangeStatementGate.ClassifiedStatement;
import com.bablsoft.accessflow.schemachange.internal.SchemaChangeStatementGate.GateResult;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSchemaChangeSetServiceTest {

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
    private SchemaChangeStatementGate gate;
    @Mock
    private SchemaChangeAuditWriter auditWriter;

    private DefaultSchemaChangeSetService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID otherOrganizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSchemaChangeSetService(changeSetRepository, statementRepository, promotionRepository,
                pipelineLookupService, gate, new SchemaChangeProperties(3, null, null, null, null, null), auditWriter);
        lenient().when(changeSetRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pipelineLookupService.findPipeline(pipelineId, organizationId))
                .thenReturn(Optional.of(pipeline()));
    }

    // ---- create ---------------------------------------------------------------------------------

    @Test
    void createStoresClassifiedStatementsInOrderWithChecksumAndWarnings() {
        var warning = finding(1, SqlReviewSeverity.WARN);
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(new GateResult(
                List.of(new ClassifiedStatement(CREATE, QueryType.DDL), new ClassifiedStatement(ALTER, QueryType.OTHER)),
                List.of(warning)));

        var view = service.create(organizationId, actorId, new CreateSchemaChangeSetCommand(pipelineId, "cs", "desc",
                inputs("  " + CREATE + ";", ALTER)));

        assertThat(view.organizationId()).isEqualTo(organizationId);
        assertThat(view.pipelineId()).isEqualTo(pipelineId);
        assertThat(view.name()).isEqualTo("cs");
        assertThat(view.description()).isEqualTo("desc");
        assertThat(view.status()).isEqualTo(SchemaChangeSetStatus.DRAFT);
        assertThat(view.createdBy()).isEqualTo(actorId);
        assertThat(view.statementsChecksum()).isEqualTo(SchemaChangeChecksum.of(List.of(CREATE, ALTER)));
        assertThat(view.statements()).extracting("sequenceOrder", "sqlText", "queryType")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, CREATE, QueryType.DDL),
                        org.assertj.core.groups.Tuple.tuple(1, ALTER, QueryType.OTHER));
        assertThat(view.reviewWarnings()).containsExactly(warning);

        var saved = ArgumentCaptor.forClass(SchemaChangeSetStatementEntity.class);
        verify(statementRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(row -> {
            assertThat(row.getId()).isNotNull();
            assertThat(row.getChangeSet().getId()).isEqualTo(view.id());
        });
        var metadata = auditMetadata(AuditAction.SCHEMA_CHANGE_SET_CREATED, view.id());
        assertThat(metadata).containsEntry("name", "cs").containsEntry("statement_count", 2)
                .containsEntry("pipeline_id", pipelineId.toString())
                .containsEntry("statements_checksum", SchemaChangeChecksum.of(List.of(CREATE, ALTER)));
    }

    @Test
    void createWithoutStatementsHasNoChecksum() {
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(GateResult.EMPTY);

        var view = service.create(organizationId, actorId, new CreateSchemaChangeSetCommand(pipelineId, "cs", null, null));

        assertThat(view.statementsChecksum()).isNull();
        assertThat(view.statements()).isEmpty();
        assertThat(view.reviewWarnings()).isEmpty();
        verify(statementRepository, never()).save(any());
    }

    @Test
    void createRejectsAPipelineOutsideTheOrganizationAs404() {
        when(pipelineLookupService.findPipeline(pipelineId, organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, inputs(CREATE))))
                .isInstanceOf(SchemaChangePipelineNotFoundException.class)
                .extracting("pipelineId").isEqualTo(pipelineId);
        verifyNoInteractions(gate, changeSetRepository);
    }

    @Test
    void createEnforcesTheStatementCapBeforeTheGate() {
        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, inputs(CREATE, ALTER, CREATE, ALTER))))
                .isInstanceOf(SchemaChangeSetStatementLimitException.class)
                .extracting("limit", "actual").containsExactly(3, 4);
        verifyNoInteractions(gate);
    }

    @Test
    void createPreChecksTheNameUnderThePipeline() {
        when(changeSetRepository.existsByOrganizationIdAndPipelineIdAndName(organizationId, pipelineId, "cs"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, null)))
                .isInstanceOf(SchemaChangeSetNameConflictException.class)
                .extracting("pipelineId", "name").containsExactly(pipelineId, "cs");
        verifyNoInteractions(gate);
    }

    @Test
    void createTranslatesTheRacedNameViolationTo409() {
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(GateResult.EMPTY);
        when(changeSetRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new SQLException("duplicate key value violates unique constraint \""
                        + DefaultSchemaChangeSetService.NAME_CONSTRAINT + "\"")));

        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, null)))
                .isInstanceOf(SchemaChangeSetNameConflictException.class);
    }

    @Test
    void createRethrowsAnUnrelatedIntegrityViolation() {
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(GateResult.EMPTY);
        var other = new DataIntegrityViolationException("other", new SQLException("some other constraint"));
        when(changeSetRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, null))).isSameAs(other);
    }

    @Test
    void createPropagatesGateRejections() {
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList()))
                .thenThrow(SchemaChangeSetStatementInvalidException.dml(0, QueryType.DELETE))
                .thenThrow(new SchemaChangeSetStatementBlockedException(List.of(finding(0, SqlReviewSeverity.BLOCK))));

        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, inputs("DELETE FROM t"))))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class);
        assertThatThrownBy(() -> service.create(organizationId, actorId,
                new CreateSchemaChangeSetCommand(pipelineId, "cs", null, inputs("DROP TABLE t"))))
                .isInstanceOf(SchemaChangeSetStatementBlockedException.class);
        verify(changeSetRepository, never()).saveAndFlush(any());
    }

    // ---- read -----------------------------------------------------------------------------------

    @Test
    void getReturnsTheSetWithItsStatementsAndNoWarnings() {
        var entity = existing(SchemaChangeSetStatus.ACTIVE);
        when(statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(entity.getId()))
                .thenReturn(List.of(row(entity, 0, CREATE), row(entity, 1, ALTER)));

        var view = service.get(organizationId, entity.getId());

        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.status()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(view.statements()).extracting("sqlText").containsExactly(CREATE, ALTER);
        assertThat(view.reviewWarnings()).isEmpty();
    }

    @Test
    void anIdFromAnotherOrganizationReadsAs404OnEveryMethod() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        var id = entity.getId();

        assertThatThrownBy(() -> service.get(otherOrganizationId, id))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
        assertThatThrownBy(() -> service.update(otherOrganizationId, actorId, id, new UpdateSchemaChangeSetCommand("x", null, null)))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
        assertThatThrownBy(() -> service.replaceStatements(otherOrganizationId, actorId, id, inputs(CREATE)))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
        assertThatThrownBy(() -> service.delete(otherOrganizationId, actorId, id))
                .isInstanceOf(SchemaChangeSetNotFoundException.class);
        verify(changeSetRepository, never()).delete(any(SchemaChangeSetEntity.class));
        verifyNoInteractions(gate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPagesWithTheFilterAndBatchLoadsStatements() {
        var first = existing(SchemaChangeSetStatus.DRAFT);
        var second = existing(SchemaChangeSetStatus.DRAFT);
        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(changeSetRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(first, second), org.springframework.data.domain.PageRequest.of(1, 2), 5));
        when(statementRepository.findAllByChangeSet_IdInOrderBySequenceOrderAsc(anyCollection()))
                .thenReturn(List.of(row(second, 0, ALTER), row(first, 0, CREATE), row(first, 1, ALTER)));

        var page = service.list(organizationId, new SchemaChangeSetListFilter(pipelineId, SchemaChangeSetStatus.DRAFT),
                PageRequest.of(1, 2));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).extracting("id").containsExactly(first.getId(), second.getId());
        assertThat(page.content().get(0).statements()).extracting("sqlText").containsExactly(CREATE, ALTER);
        assertThat(page.content().get(1).statements()).extracting("sqlText").containsExactly(ALTER);
        assertThat(page.content()).allSatisfy(v -> assertThat(v.reviewWarnings()).isEmpty());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        var idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(statementRepository).findAllByChangeSet_IdInOrderBySequenceOrderAsc(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(first.getId(), second.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOfAnEmptyPageSkipsTheStatementLoad() {
        when(changeSetRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var page = service.list(organizationId, SchemaChangeSetListFilter.none(), PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(statementRepository, never()).findAllByChangeSet_IdInOrderBySequenceOrderAsc(anyCollection());
    }

    // ---- update ---------------------------------------------------------------------------------

    @Test
    void updateChangesNameAndDescriptionAndLeavesNullsAlone() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        entity.setDescription("old");

        var renamed = service.update(organizationId, actorId, entity.getId(), new UpdateSchemaChangeSetCommand("new-name", null, null));
        var described = service.update(organizationId, actorId, entity.getId(), new UpdateSchemaChangeSetCommand(null, "new desc", null));

        assertThat(renamed.name()).isEqualTo("new-name");
        assertThat(renamed.description()).isEqualTo("old");
        assertThat(described.name()).isEqualTo("new-name");
        assertThat(described.description()).isEqualTo("new desc");
        assertThat(described.reviewWarnings()).isEmpty();
        verify(changeSetRepository).existsByOrganizationIdAndPipelineIdAndName(organizationId, pipelineId, "new-name");
        verifyNoInteractions(gate);
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, org.mockito.Mockito.times(2)).record(eq(AuditAction.SCHEMA_CHANGE_SET_UPDATED),
                eq(AuditResourceType.SCHEMA_CHANGE_SET), eq(entity.getId()), eq(organizationId), eq(actorId),
                metadata.capture(), isNull(), isNull());
        assertThat(metadata.getAllValues().get(0)).containsEntry("changed_fields", List.of("name"));
        assertThat(metadata.getAllValues().get(1)).containsEntry("changed_fields", List.of("description"));
    }

    @Test
    void updateToTheSameNameSkipsTheConflictCheck() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);

        service.update(organizationId, actorId, entity.getId(), new UpdateSchemaChangeSetCommand(entity.getName(), null, null));

        verify(changeSetRepository, never()).existsByOrganizationIdAndPipelineIdAndName(any(), any(), any());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void updateRejectsARenameToAnExistingName() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        when(changeSetRepository.existsByOrganizationIdAndPipelineIdAndName(organizationId, pipelineId, "taken"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.update(organizationId, actorId, entity.getId(),
                new UpdateSchemaChangeSetCommand("taken", null, null)))
                .isInstanceOf(SchemaChangeSetNameConflictException.class);
        verify(changeSetRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateArchivesFromDraftOrActiveAndTreatsTheSameStatusAsANoOp() {
        var draft = existing(SchemaChangeSetStatus.DRAFT);
        var active = existing(SchemaChangeSetStatus.ACTIVE);

        assertThat(service.update(organizationId, actorId, draft.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.DRAFT)).status())
                .isEqualTo(SchemaChangeSetStatus.DRAFT);
        assertThat(service.update(organizationId, actorId, draft.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.ARCHIVED)).status())
                .isEqualTo(SchemaChangeSetStatus.ARCHIVED);
        assertThat(service.update(organizationId, actorId, active.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.ARCHIVED)).status())
                .isEqualTo(SchemaChangeSetStatus.ARCHIVED);
        assertThat(service.update(organizationId, actorId, active.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.ARCHIVED)).status())
                .isEqualTo(SchemaChangeSetStatus.ARCHIVED);
    }

    @Test
    void updateRefusesAnyOtherStatusTransition() {
        var draft = existing(SchemaChangeSetStatus.DRAFT);
        var archived = existing(SchemaChangeSetStatus.ARCHIVED);

        assertThatThrownBy(() -> service.update(organizationId, actorId, draft.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.ACTIVE)))
                .isInstanceOf(SchemaChangeSetStatusTransitionException.class)
                .extracting("currentStatus", "requestedStatus")
                .containsExactly(SchemaChangeSetStatus.DRAFT, SchemaChangeSetStatus.ACTIVE);
        assertThatThrownBy(() -> service.update(organizationId, actorId, archived.getId(),
                new UpdateSchemaChangeSetCommand(null, null, SchemaChangeSetStatus.DRAFT)))
                .isInstanceOf(SchemaChangeSetStatusTransitionException.class);
        verify(changeSetRepository, never()).saveAndFlush(any());
    }

    @Test
    void descriptiveEditsAreAllowedOnFrozenAndArchivedSets() {
        var archived = existing(SchemaChangeSetStatus.ARCHIVED);
        var frozen = existing(SchemaChangeSetStatus.ACTIVE);
        lenient().when(promotionRepository.existsByChangeSet_IdAndStatusIn(eq(frozen.getId()), anyCollection()))
                .thenReturn(true);

        assertThat(service.update(organizationId, actorId, archived.getId(),
                new UpdateSchemaChangeSetCommand(null, "still editable", null)).description()).isEqualTo("still editable");
        assertThat(service.update(organizationId, actorId, frozen.getId(),
                new UpdateSchemaChangeSetCommand(null, "still editable", null)).description()).isEqualTo("still editable");
        verify(promotionRepository, never()).existsByChangeSet_IdAndStatusIn(any(), anyCollection());
    }

    // ---- replaceStatements ----------------------------------------------------------------------

    @Test
    void replaceStatementsDeletesThenReinsertsInOrderAndRecomputesTheChecksum() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        entity.setStatementsChecksum(SchemaChangeChecksum.of(List.of(CREATE, ALTER)));
        var warning = finding(0, SqlReviewSeverity.WARN);
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(new GateResult(
                List.of(new ClassifiedStatement(ALTER, QueryType.DDL), new ClassifiedStatement(CREATE, QueryType.DDL)),
                List.of(warning)));

        var view = service.replaceStatements(organizationId, actorId, entity.getId(), inputs(ALTER, CREATE));

        assertThat(view.statements()).extracting("sequenceOrder", "sqlText")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, ALTER), org.assertj.core.groups.Tuple.tuple(1, CREATE));
        assertThat(view.statementsChecksum()).isEqualTo(SchemaChangeChecksum.of(List.of(ALTER, CREATE)))
                .isNotEqualTo(SchemaChangeChecksum.of(List.of(CREATE, ALTER)));
        assertThat(view.reviewWarnings()).containsExactly(warning);
        var order = inOrder(statementRepository, changeSetRepository);
        order.verify(statementRepository).deleteAllByChangeSetId(entity.getId());
        order.verify(statementRepository, org.mockito.Mockito.times(2)).save(any());
        order.verify(changeSetRepository).saveAndFlush(entity);
        var metadata = auditMetadata(AuditAction.SCHEMA_CHANGE_SET_STATEMENTS_REPLACED, entity.getId());
        assertThat(metadata).containsEntry("statement_count", 2)
                .containsEntry("statements_checksum", SchemaChangeChecksum.of(List.of(ALTER, CREATE)));
    }

    @Test
    void replaceStatementsWithAnEmptyListClearsTheChecksum() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        entity.setStatementsChecksum("a".repeat(64));
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(GateResult.EMPTY);

        var view = service.replaceStatements(organizationId, actorId, entity.getId(), List.of());

        assertThat(view.statements()).isEmpty();
        assertThat(view.statementsChecksum()).isNull();
        verify(statementRepository).deleteAllByChangeSetId(entity.getId());
        assertThat(auditMetadata(AuditAction.SCHEMA_CHANGE_SET_STATEMENTS_REPLACED, entity.getId()))
                .containsEntry("statement_count", 0).doesNotContainKey("statements_checksum");
    }

    @Test
    void replaceStatementsRefusesAnArchivedSet() {
        var entity = existing(SchemaChangeSetStatus.ARCHIVED);

        assertThatThrownBy(() -> service.replaceStatements(organizationId, actorId, entity.getId(), inputs(CREATE)))
                .isInstanceOf(SchemaChangeSetArchivedException.class);
        verifyNoInteractions(gate, promotionRepository);
        verify(statementRepository, never()).deleteAllByChangeSetId(any());
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class,
            names = {"PENDING", "IN_REVIEW", "APPROVED", "APPLIED", "PARTIALLY_APPLIED"})
    void replaceStatementsRefusesAFrozenSet(SchemaChangePromotionStatus freezing) {
        var entity = existing(SchemaChangeSetStatus.ACTIVE);
        when(promotionRepository.existsByChangeSet_IdAndStatusIn(entity.getId(),
                DefaultSchemaChangeSetService.FREEZING_STATUSES)).thenReturn(true);

        assertThat(DefaultSchemaChangeSetService.FREEZING_STATUSES).contains(freezing);
        assertThatThrownBy(() -> service.replaceStatements(organizationId, actorId, entity.getId(), inputs(CREATE)))
                .isInstanceOf(SchemaChangeSetFrozenException.class)
                .extracting("changeSetId").isEqualTo(entity.getId());
        verifyNoInteractions(gate);
        verify(statementRepository, never()).deleteAllByChangeSetId(any());
    }

    @Test
    void failedAndCancelledPromotionsDoNotFreeze() {
        assertThat(DefaultSchemaChangeSetService.FREEZING_STATUSES)
                .doesNotContain(SchemaChangePromotionStatus.FAILED, SchemaChangePromotionStatus.CANCELLED);
        var entity = existing(SchemaChangeSetStatus.ACTIVE);
        when(promotionRepository.existsByChangeSet_IdAndStatusIn(entity.getId(),
                DefaultSchemaChangeSetService.FREEZING_STATUSES)).thenReturn(false);
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList())).thenReturn(GateResult.EMPTY);

        service.replaceStatements(organizationId, actorId, entity.getId(), List.of());

        verify(statementRepository).deleteAllByChangeSetId(entity.getId());
    }

    @Test
    void replaceStatementsEnforcesTheCapAfterTheFreezeCheck() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);

        assertThatThrownBy(() -> service.replaceStatements(organizationId, actorId, entity.getId(),
                inputs(CREATE, ALTER, CREATE, ALTER)))
                .isInstanceOf(SchemaChangeSetStatementLimitException.class);
        verify(promotionRepository).existsByChangeSet_IdAndStatusIn(entity.getId(),
                DefaultSchemaChangeSetService.FREEZING_STATUSES);
        verifyNoInteractions(gate);
    }

    @Test
    void replaceStatementsPropagatesGateRejectionsWithoutTouchingRows() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);
        when(gate.validate(eq(organizationId), eq(pipelineId), anyList()))
                .thenThrow(SchemaChangeSetStatementInvalidException.multipleStatements(0));

        assertThatThrownBy(() -> service.replaceStatements(organizationId, actorId, entity.getId(), inputs(CREATE + "; " + ALTER)))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class);
        verify(statementRepository, never()).deleteAllByChangeSetId(any());
        verify(changeSetRepository, never()).saveAndFlush(any());
    }

    // ---- delete ---------------------------------------------------------------------------------

    @Test
    void deleteRemovesAnUnfrozenSet() {
        var entity = existing(SchemaChangeSetStatus.DRAFT);

        service.delete(organizationId, actorId, entity.getId());

        var order = inOrder(changeSetRepository, auditWriter);
        order.verify(changeSetRepository).delete(entity);
        order.verify(changeSetRepository).flush();
        order.verify(auditWriter).record(eq(AuditAction.SCHEMA_CHANGE_SET_DELETED),
                eq(AuditResourceType.SCHEMA_CHANGE_SET), eq(entity.getId()), eq(organizationId), eq(actorId),
                any(), isNull(), isNull());
    }

    @Test
    void deleteRefusesAFrozenSet() {
        var entity = existing(SchemaChangeSetStatus.ACTIVE);
        when(promotionRepository.existsByChangeSet_IdAndStatusIn(entity.getId(),
                DefaultSchemaChangeSetService.FREEZING_STATUSES)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(organizationId, actorId, entity.getId()))
                .isInstanceOf(SchemaChangeSetFrozenException.class);
        verify(changeSetRepository, never()).delete(any(SchemaChangeSetEntity.class));
        verifyNoInteractions(auditWriter);
    }

    // ---- helpers --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata(AuditAction action, UUID changeSetId) {
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(action), eq(AuditResourceType.SCHEMA_CHANGE_SET), eq(changeSetId),
                eq(organizationId), eq(actorId), metadata.capture(), isNull(), isNull());
        return metadata.getValue();
    }

    private SchemaChangeSetEntity existing(SchemaChangeSetStatus status) {
        var entity = new SchemaChangeSetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(pipelineId);
        entity.setName("cs-" + entity.getId());
        entity.setStatus(status);
        entity.setCreatedBy(actorId);
        lenient().when(changeSetRepository.findByIdAndOrganizationId(entity.getId(), organizationId))
                .thenReturn(Optional.of(entity));
        lenient().when(changeSetRepository.findByIdAndOrganizationId(entity.getId(), otherOrganizationId))
                .thenReturn(Optional.empty());
        return entity;
    }

    private static SchemaChangeSetStatementEntity row(SchemaChangeSetEntity set, int order, String sql) {
        var row = new SchemaChangeSetStatementEntity();
        row.setId(UUID.randomUUID());
        row.setChangeSet(set);
        row.setSequenceOrder(order);
        row.setSqlText(sql);
        row.setQueryType(QueryType.DDL);
        return row;
    }

    private DeploymentPipelineView pipeline() {
        return new DeploymentPipelineView(pipelineId, organizationId, "pipe", null, null, null, null, true, null, true,
                Instant.EPOCH, Instant.EPOCH);
    }

    private SchemaChangeStatementFinding finding(int index, SqlReviewSeverity severity) {
        return new SchemaChangeStatementFinding(index, datasourceId,
                new SqlReviewFinding("ddl_statement", severity, 0, 1, Map.of()));
    }

    private static List<SchemaChangeSetStatementInput> inputs(String... sql) {
        return java.util.Arrays.stream(sql).map(SchemaChangeSetStatementInput::new).toList();
    }
}
