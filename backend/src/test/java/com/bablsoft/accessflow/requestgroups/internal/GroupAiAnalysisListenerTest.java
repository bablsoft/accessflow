package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GroupAiAnalysisListener listener;
    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        listener = new GroupAiAnalysisListener(groupRepository, itemRepository, aiAnalyzerService,
                apiAssistService, aiAnalysisPersistenceService, reviewPlanResolver, stateService,
                eventPublisher, objectMapper);
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
}
