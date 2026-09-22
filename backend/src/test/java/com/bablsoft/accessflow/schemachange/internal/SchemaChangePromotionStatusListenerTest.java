package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaChangePromotionStatusListenerTest {

    @Mock
    private SchemaChangeSetPromotionRepository promotionRepository;
    @Mock
    private RequestGroupService requestGroupService;
    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID promoterId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-22T11:00:00Z");

    private SchemaChangePromotionStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new SchemaChangePromotionStatusListener(promotionRepository, requestGroupService,
                datasourceAdminService, new ObjectMapper(), new SchemaChangeAuditWriter(auditLogService),
                eventPublisher, Clock.fixed(now, ZoneOffset.UTC));
        lenient().when(promotionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * The group's execution transitions are published with no surrounding transaction, so a plain
     * after-commit listener would never see them.
     */
    @Test
    void runsAsynchronouslyInItsOwnTransactionAndFallsBackOffTransaction() throws Exception {
        var method = SchemaChangePromotionStatusListener.class
                .getDeclaredMethod("onRequestGroupStatusChanged", RequestGroupStatusChangedEvent.class);

        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        var listenerAnnotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listenerAnnotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listenerAnnotation.fallbackExecution()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_REVIEW,IN_REVIEW",
            "APPROVED,APPROVED",
            "PARTIALLY_EXECUTED,PARTIALLY_APPLIED",
            "FAILED,FAILED",
            "REJECTED,CANCELLED",
            "TIMED_OUT,CANCELLED",
            "CANCELLED,CANCELLED"
    })
    void projectsEachGroupStatusOntoThePromotion(RequestGroupStatus group, SchemaChangePromotionStatus expected) {
        var promotion = givenPromotion(SchemaChangePromotionStatus.PENDING);
        lenient().when(requestGroupService.get(groupId, organizationId, promoterId, true))
                .thenReturn(groupView(List.of()));

        listener.onRequestGroupStatusChanged(event(group));

        assertThat(promotion.getStatus()).isEqualTo(expected);
        verify(promotionRepository).saveAndFlush(promotion);
        var published = ArgumentCaptor.forClass(SchemaChangePromotionStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().oldStatus()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        assertThat(published.getValue().newStatus()).isEqualTo(expected);
        assertThat(published.getValue().promotionId()).isEqualTo(promotion.getId());
    }

    @ParameterizedTest
    @EnumSource(value = RequestGroupStatus.class, names = {"DRAFT", "PENDING_AI", "EXECUTING"})
    void ignoresGroupStatesWithNoPromotionCounterpart(RequestGroupStatus group) {
        listener.onRequestGroupStatusChanged(event(group));

        verifyNoInteractions(promotionRepository, eventPublisher, auditLogService);
    }

    @Test
    void ignoresAGroupThisModuleDidNotCreate() {
        when(promotionRepository.findByRequestGroupIdForUpdate(groupId)).thenReturn(Optional.empty());

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.EXECUTED));

        verify(promotionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, auditLogService, datasourceAdminService);
    }

    /** Group events are async and may reorder: a promotion never moves backwards. */
    @Test
    void ignoresAnEventThatWouldMoveThePromotionBackwards() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPLIED);

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.APPROVED));

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        verify(promotionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, auditLogService);
    }

    /** The cancel endpoint already wrote the row and its audit; the projection must not duplicate it. */
    @Test
    void ignoresACancelledEventForAnAlreadyCancelledPromotion() {
        givenPromotion(SchemaChangePromotionStatus.CANCELLED);

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.CANCELLED));

        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void appliedStampsTheSnapshotAndTheAppliedTimestamp() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPROVED);
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenReturn(new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                        List.of(new DatabaseSchemaView.Table("t",
                                List.of(new DatabaseSchemaView.Column("id", "int4", false, true)), List.of()))))));

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.EXECUTED));

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        assertThat(promotion.getAppliedAt()).isEqualTo(now);
        assertThat(promotion.getSnapshotTakenAt()).isEqualTo(now);
        assertThat(promotion.getSchemaSnapshot()).contains("\"public\"").contains("\"t\"");
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.SCHEMA_CHANGE_PROMOTION_APPLIED);
        assertThat(entry.getValue().actorId()).isNull();
        assertThat(entry.getValue().metadata()).containsEntry("trigger", "request_group")
                .containsEntry("group_status", "EXECUTED");
    }

    /** An unreachable target must not lose the transition — only the baseline. */
    @Test
    void appliedWithoutIntrospectionKeepsTheTransitionAndLeavesTheSnapshotNull() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPROVED);
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenThrow(new IllegalStateException("connection refused"));

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.EXECUTED));

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        assertThat(promotion.getAppliedAt()).isEqualTo(now);
        assertThat(promotion.getSchemaSnapshot()).isNull();
        assertThat(promotion.getSnapshotTakenAt()).isNull();
    }

    @Test
    void failedCopiesTheFirstFailedMembersMessageAndTakesNoSnapshot() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPROVED);
        when(requestGroupService.get(groupId, organizationId, promoterId, true)).thenReturn(groupView(List.of(
                item(0, RequestGroupItemStatus.EXECUTED, null),
                item(1, RequestGroupItemStatus.FAILED, "relation \"t\" does not exist"),
                item(2, RequestGroupItemStatus.SKIPPED, null))));

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.PARTIALLY_EXECUTED));

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.PARTIALLY_APPLIED);
        assertThat(promotion.getErrorMessage()).isEqualTo("relation \"t\" does not exist");
        assertThat(promotion.getSchemaSnapshot()).isNull();
        verifyNoInteractions(datasourceAdminService);
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.SCHEMA_CHANGE_PROMOTION_PARTIALLY_APPLIED);
        assertThat(entry.getValue().metadata()).containsEntry("error_message", "relation \"t\" does not exist");
    }

    @Test
    void failedWithoutAReadableGroupStillRecordsTheTransition() {
        var promotion = givenPromotion(SchemaChangePromotionStatus.APPROVED);
        when(requestGroupService.get(groupId, organizationId, promoterId, true))
                .thenThrow(new IllegalStateException("gone"));

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.FAILED));

        assertThat(promotion.getStatus()).isEqualTo(SchemaChangePromotionStatus.FAILED);
        assertThat(promotion.getErrorMessage()).isNull();
    }

    @Test
    void auditsNothingForTheNonTerminalProjections() {
        givenPromotion(SchemaChangePromotionStatus.PENDING);

        listener.onRequestGroupStatusChanged(event(RequestGroupStatus.PENDING_REVIEW));

        verifyNoInteractions(auditLogService);
    }

    /** A projection failure must never surface to the approver or stall the group executor. */
    @Test
    void swallowsAnyFailureWhileProjecting() {
        when(promotionRepository.findByRequestGroupIdForUpdate(groupId))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onRequestGroupStatusChanged(event(RequestGroupStatus.EXECUTED)))
                .doesNotThrowAnyException();
    }

    private SchemaChangeSetPromotionEntity givenPromotion(SchemaChangePromotionStatus status) {
        var changeSet = new SchemaChangeSetEntity();
        changeSet.setId(changeSetId);
        changeSet.setOrganizationId(organizationId);
        changeSet.setStatus(SchemaChangeSetStatus.ACTIVE);

        var promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(UUID.randomUUID());
        promotion.setOrganizationId(organizationId);
        promotion.setChangeSet(changeSet);
        promotion.setEnvironmentId(environmentId);
        promotion.setDatasourceId(datasourceId);
        promotion.setRequestGroupId(groupId);
        promotion.setStatus(status);
        promotion.setStatementsChecksum("a".repeat(64));
        promotion.setPromotedBy(promoterId);
        when(promotionRepository.findByRequestGroupIdForUpdate(groupId)).thenReturn(Optional.of(promotion));
        return promotion;
    }

    private RequestGroupStatusChangedEvent event(RequestGroupStatus newStatus) {
        return new RequestGroupStatusChangedEvent(groupId, promoterId, RequestGroupStatus.PENDING_AI, newStatus);
    }

    private RequestGroupView groupView(List<RequestGroupItemView> items) {
        return new RequestGroupView(groupId, organizationId, promoterId, "Admin", "schema-change", null,
                RequestGroupStatus.EXECUTED, false, null, null, null, 1, 1, null, now, now, now, now, items, null);
    }

    private RequestGroupItemView item(int order, RequestGroupItemStatus status, String errorMessage) {
        return new RequestGroupItemView(UUID.randomUUID(), order, RequestGroupTargetKind.QUERY, datasourceId, "db",
                "CREATE TABLE t (id INT)", QueryType.DDL, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, status, null, null, errorMessage, null, null, List.of());
    }
}
