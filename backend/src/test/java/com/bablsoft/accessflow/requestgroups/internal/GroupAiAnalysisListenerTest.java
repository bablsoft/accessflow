package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupSubmittedEvent;
import com.bablsoft.accessflow.requestgroups.internal.GroupReviewPlanResolver.GroupReviewResolution;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupAiAnalysisListenerTest {

    @Mock private RequestGroupRepository groupRepository;
    @Mock private RequestGroupItemRepository itemRepository;
    @Mock private AiAnalyzerService aiAnalyzerService;
    @Mock private ApiAssistService apiAssistService;
    @Mock private AiAnalysisPersistenceService aiAnalysisPersistenceService;
    @Mock private GroupReviewPlanResolver reviewPlanResolver;
    @Mock private RequestGroupStateService stateService;
    @Mock private SqlReviewFindingService sqlReviewFindingService;
    @Mock private AuditLogService auditLogService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GroupAiAnalysisListener listener;
    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        listener = new GroupAiAnalysisListener(groupRepository, itemRepository, aiAnalyzerService,
                apiAssistService, aiAnalysisPersistenceService, reviewPlanResolver, stateService,
                sqlReviewFindingService, auditLogService, eventPublisher, objectMapper);
        group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setStatus(RequestGroupStatus.PENDING_AI);
        lenient().when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        lenient().when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RequestGroupItemEntity queryItem() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        return item;
    }

    private RequestGroupItemEntity apiItem() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(RequestGroupTargetKind.API_CALL);
        item.setApiConnectorId(UUID.randomUUID());
        item.setVerb("GET");
        item.setRequestPath("/x");
        return item;
    }

    private AiAnalysisResult result(int score, RiskLevel level) {
        return new AiAnalysisResult(score, level, "ok", List.of(), false, null,
                AiProviderType.ANTHROPIC, "m", 1, 1, List.of(), List.of());
    }

    @Test
    void analyzesMembersSetsAggregateMaxAndRoutesToReview() {
        var q = queryItem();
        var a = apiItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q, a));
        when(aiAnalyzerService.analyzePreview(eq(q.getDatasourceId()), eq("SELECT 1"), any(), any(), eq(true)))
                .thenReturn(result(20, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(eq(q.getId()), any()))
                .thenReturn(UUID.randomUUID());
        when(apiAssistService.analyzeDetailed(eq(a.getApiConnectorId()), any(), any(), eq(true), any()))
                .thenReturn(result(90, RiskLevel.HIGH));
        when(aiAnalysisPersistenceService.persistForGroupItem(eq(a.getId()), any()))
                .thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(true, 2, Set.of(), Set.of()));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        assertThat(group.getAiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(group.getAiRiskScore()).isEqualTo(90);
        assertThat(group.getRequiredApprovals()).isEqualTo(2);
        assertThat(q.getAiAnalysisId()).isNotNull();
        assertThat(a.getAiAnalysisId()).isNotNull();
        verify(aiAnalysisPersistenceService).persistForGroupItem(eq(a.getId()), any());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
    }

    @Test
    void apiMemberAnalysisFailureIsFailSafe() {
        var a = apiItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(a));
        when(apiAssistService.analyzeDetailed(eq(a.getApiConnectorId()), any(), any(), eq(true), any()))
                .thenThrow(new RuntimeException("no ai config"));
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(true, 1, Set.of(), Set.of()));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        assertThat(a.getAiAnalysisId()).isNull();
        assertThat(a.getAiRiskLevel()).isNull();
        verify(aiAnalysisPersistenceService, never()).persistForGroupItem(any(), any());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
    }

    @Test
    void routesToApprovedWhenNoPlanRequiresHumanApproval() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(false, 0, Set.of(), Set.of()));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.APPROVED);
    }

    @Test
    void failSafeWhenAnalysisThrowsStillRoutes() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenThrow(new RuntimeException("no ai config"));
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(true, 1, Set.of(), Set.of()));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        assertThat(q.getAiRiskLevel()).isNull();
        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
    }

    @Test
    void resolverFailureFailsClosedToReview() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any())).thenThrow(new RuntimeException("boom"));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
    }

    @Test
    void ignoresGroupNotPendingAi() {
        group.setStatus(RequestGroupStatus.APPROVED);
        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));
        verify(stateService, never()).apply(any(), any());
    }

    // ── SQL review BLOCK guard (#864) ─────────────────────────────────────────

    @Test
    void aMemberBlockForcesTheWholeGroupToReviewAndAuditsOnce() {
        var q = queryItem();
        var a = apiItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q, a));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(apiAssistService.analyzeDetailed(any(), any(), any(), eq(true), any()))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(false, 0, Set.of(), Set.of()));
        when(sqlReviewFindingService.findByGroupItems(List.of(q.getId(), a.getId())))
                .thenReturn(Map.of(q.getId(), List.of(
                        new SqlReviewFinding("select_star", SqlReviewSeverity.BLOCK, 0, 1, Map.of()),
                        new SqlReviewFinding("missing_limit_on_select", SqlReviewSeverity.WARN, 0, 1,
                                Map.of()))));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
        verify(stateService, never()).apply(group, RequestGroupStatus.APPROVED);
        assertThat(group.getRequiredApprovals()).isEqualTo(1);
        var audit = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.SQL_REVIEW_BLOCKED);
        assertThat(audit.getValue().resourceType()).isEqualTo(AuditResourceType.REQUEST_GROUP);
        assertThat(audit.getValue().resourceId()).isEqualTo(group.getId());
        assertThat(audit.getValue().actorId()).isNull();
        assertThat(audit.getValue().metadata())
                .containsEntry("trigger", "sql_review")
                .containsEntry("blocking_rule_ids", List.of("select_star"))
                .containsEntry("blocking_item_ids", List.of(q.getId()))
                .containsEntry("suppressed_paths", List.of("GROUP_REVIEW_PLAN"));
    }

    @Test
    void warnOnlyFindingsLeaveTheGroupFastPathAlone() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(false, 0, Set.of(), Set.of()));
        when(sqlReviewFindingService.findByGroupItems(List.of(q.getId())))
                .thenReturn(Map.of(q.getId(), List.of(new SqlReviewFinding("select_star",
                        SqlReviewSeverity.WARN, 0, 1, Map.of()))));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.APPROVED);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void aBlockOnAGroupAlreadyHeadedToReviewWritesNoAuditRow() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(true, 2, Set.of(), Set.of()));
        when(sqlReviewFindingService.findByGroupItems(List.of(q.getId())))
                .thenReturn(Map.of(q.getId(), List.of(new SqlReviewFinding("select_star",
                        SqlReviewSeverity.BLOCK, 0, 1, Map.of()))));

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
        assertThat(group.getRequiredApprovals()).isEqualTo(2);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void anAuditWriteFailureDoesNotUndoTheGroupTransition() {
        var q = queryItem();
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(q));
        when(aiAnalyzerService.analyzePreview(any(), any(), any(), any(), eq(true)))
                .thenReturn(result(10, RiskLevel.LOW));
        when(aiAnalysisPersistenceService.persistForGroupItem(any(), any())).thenReturn(UUID.randomUUID());
        when(reviewPlanResolver.resolve(eq(group), any()))
                .thenReturn(new GroupReviewResolution(false, 0, Set.of(), Set.of()));
        when(sqlReviewFindingService.findByGroupItems(List.of(q.getId())))
                .thenReturn(Map.of(q.getId(), List.of(new SqlReviewFinding("select_star",
                        SqlReviewSeverity.BLOCK, 0, 1, Map.of()))));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down"))
                .when(auditLogService).record(any());

        listener.onSubmitted(new RequestGroupSubmittedEvent(group.getId()));

        verify(stateService).apply(group, RequestGroupStatus.PENDING_REVIEW);
    }
}
