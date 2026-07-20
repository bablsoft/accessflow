package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService.ApiConnectorPermissionLookupView;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupSubmittedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRequestGroupServiceCrudTest {

    @Mock private RequestGroupRepository groupRepository;
    @Mock private RequestGroupItemRepository itemRepository;
    @Mock private RequestGroupStateService stateService;
    @Mock private GroupExecutionService executionService;
    @Mock private QueryParser queryParser;
    @Mock private DatasourceLookupService datasourceLookupService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private DatasourceUserPermissionLookupService datasourcePermissionLookupService;
    @Mock private ApiConnectorPermissionLookupService apiConnectorPermissionLookupService;
    @Mock private ApiConnectorAdminService apiConnectorAdminService;
    @Mock private UserQueryService userQueryService;
    @Mock private AuditLogService auditLogService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultRequestGroupService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultRequestGroupService(groupRepository, itemRepository, stateService,
                executionService, queryParser, datasourceLookupService, aiAnalysisLookupService,
                datasourcePermissionLookupService, apiConnectorPermissionLookupService,
                apiConnectorAdminService, userQueryService, auditLogService, eventPublisher,
                objectMapper);
        lenient().when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userQueryService.findById(any())).thenReturn(Optional.of(new UserView(userId,
                "u@x.io", "Dana", UserRoleType.ANALYST, orgId, true, null, null, Instant.now(), "en",
                false, Instant.now())));
        lenient().when(datasourceLookupService.findRef(any()))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "db")));
        lenient().when(apiConnectorAdminService.getForAdmin(any(), any()))
                .thenThrow(new RuntimeException("connector gone")); // assembleView swallows this
    }

    private DatasourceUserPermissionView dsPerm(boolean read, boolean write, boolean bg) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, read, write,
                false, bg, List.of(), List.of(), List.of(), null);
    }

    private ApiConnectorPermissionLookupView apiPerm(boolean read, boolean write, boolean bg) {
        return new ApiConnectorPermissionLookupView(connectorId, userId, read, write, bg, false, List.of(), null);
    }

    private RequestGroupItemInput queryInput() {
        return new RequestGroupItemInput(
                com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY, 0, datasourceId,
                "SELECT 1", false, null, null, null, null, null, null, null, null, null, null, null);
    }

    private RequestGroupItemInput apiInput() {
        return new RequestGroupItemInput(
                com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.API_CALL, 1, null, null,
                false, connectorId, "op", "GET", "/x", null, null, RequestGroupItemInput.ApiBodyKind.RAW,
                null, null, null, null);
    }

    private RequestGroupEntity draftGroup() {
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(orgId);
        group.setSubmittedBy(userId);
        group.setStatus(RequestGroupStatus.DRAFT);
        return group;
    }

    @Test
    void createDraftPersistsQueryAndApiMembers() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, false, false)));
        when(apiConnectorPermissionLookupService.findFor(connectorId, userId))
                .thenReturn(Optional.of(apiPerm(true, false, false)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(any()))
                .thenReturn(List.of(new RequestGroupItemEntity()));

        var view = service.createDraft(new CreateRequestGroupCommand(orgId, userId, false, "bundle",
                "desc", true, List.of(queryInput(), apiInput())));

        assertThat(view.name()).isEqualTo("bundle");
        verify(itemRepository, org.mockito.Mockito.times(2)).save(any());
    }

    private RequestGroupItemEntity persistedApiItem() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.API_CALL);
        item.setApiConnectorId(connectorId);
        item.setVerb("POST");
        item.setRequestPath("/v1/tickets");
        item.setRequestHeaders("{\"X-Trace\":\"1\"}");
        item.setQueryParams("{\"dryRun\":\"true\"}");
        item.setBodyType(com.bablsoft.accessflow.apigov.api.ApiBodyType.RAW);
        item.setRequestContentType("application/json");
        item.setRequestBody("{\"a\":1}");
        item.setStatus(com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus.PENDING);
        return item;
    }

    @Test
    void getReturnsApiCompositionForDraftEditing() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(persistedApiItem()));

        var view = service.get(group.getId(), orgId, userId, false);

        var item = view.items().get(0);
        assertThat(item.requestHeaders()).containsEntry("X-Trace", "1");
        assertThat(item.queryParams()).containsEntry("dryRun", "true");
        assertThat(item.bodyType()).isEqualTo(com.bablsoft.accessflow.apigov.api.ApiBodyType.RAW);
        assertThat(item.requestBody()).isEqualTo("{\"a\":1}");
    }

    @Test
    void listOmitsApiComposition() {
        var group = draftGroup();
        when(groupRepository.findAll(org.mockito.ArgumentMatchers.<Specification<RequestGroupEntity>>any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(group)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(persistedApiItem()));

        var page = service.list(new RequestGroupListFilter(orgId, null, null),
                new PageRequest(0, 20, List.of()));

        var item = page.content().get(0).items().get(0);
        assertThat(item.requestHeaders()).isEmpty();
        assertThat(item.bodyType()).isNull();
        assertThat(item.requestBody()).isNull();
    }

    @Test
    void submitNormalTransitionsToPendingAiAndPublishesEvent() {
        var group = draftGroup();
        var item = new RequestGroupItemEntity();
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(item));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, false, false)));

        var result = service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, false,
                false, null, "1.2.3.4", "ua"));

        assertThat(result.id()).isEqualTo(group.getId());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_AI);
        verify(eventPublisher).publishEvent(any(RequestGroupSubmittedEvent.class));
    }

    @Test
    void breakGlassSubmitForceApprovesAndExecutes() {
        var group = draftGroup();
        var item = new RequestGroupItemEntity();
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(item));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, false, true)));

        service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, false, true, null,
                null, null));

        verify(stateService).apply(group, RequestGroupStatus.APPROVED);
        verify(executionService).execute(group.getId(), userId, "break_glass");
    }

    @Test
    void cancelByNonSubmitterIsRejected() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.cancel(group.getId(), orgId, UUID.randomUUID()))
                .isInstanceOf(RequestGroupPermissionException.class);
    }

    @Test
    void cancelFromExecutedIsIllegal() {
        var group = draftGroup();
        group.setStatus(RequestGroupStatus.EXECUTED);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.cancel(group.getId(), orgId, userId))
                .isInstanceOf(IllegalRequestGroupStateException.class);
    }

    @Test
    void cancelPendingReviewTransitionsToCancelled() {
        var group = draftGroup();
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));

        service.cancel(group.getId(), orgId, userId);

        verify(stateService).apply(group, RequestGroupStatus.CANCELLED);
    }

    @Test
    void deleteDraftRemovesGroupAndItems() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));

        service.deleteDraft(group.getId(), orgId, userId);

        verify(itemRepository).deleteByGroupId(group.getId());
        verify(groupRepository).delete(group);
    }

    @Test
    void deleteNonDraftIsIllegal() {
        var group = draftGroup();
        group.setStatus(RequestGroupStatus.APPROVED);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.deleteDraft(group.getId(), orgId, userId))
                .isInstanceOf(IllegalRequestGroupStateException.class);
    }

    @Test
    void executeByForeignNonAdminIsRejected() {
        var group = draftGroup();
        group.setStatus(RequestGroupStatus.APPROVED);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> service.execute(group.getId(), orgId, UUID.randomUUID(), false))
                .isInstanceOf(RequestGroupPermissionException.class);
    }

    @Test
    void getReturnsView() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of());

        assertThat(service.get(group.getId(), orgId, userId, false).id()).isEqualTo(group.getId());
    }

    @Test
    void getEmbedsFullMemberAnalysis() {
        var group = draftGroup();
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setStatus(com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus.PENDING);
        item.setAiAnalysisId(UUID.randomUUID());
        var detail = new QueryDetailView.AiAnalysisDetail(item.getAiAnalysisId(), null, 40,
                "Reads one row", "[]", "[]", false, null, null, "gpt-4o", 10, 5, false, null);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(item));
        when(aiAnalysisLookupService.findDetailById(item.getAiAnalysisId()))
                .thenReturn(Optional.of(detail));

        var view = service.get(group.getId(), orgId, userId, false);

        assertThat(view.items().get(0).aiAnalysis()).isSameAs(detail);
    }

    @Test
    void listDoesNotLoadMemberAnalyses() {
        var group = draftGroup();
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setAiAnalysisId(UUID.randomUUID());
        when(groupRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<RequestGroupEntity>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(group)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(item));

        var page = service.list(new com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter(
                orgId, null, null), new com.bablsoft.accessflow.core.api.PageRequest(0, 20, List.of()));

        assertThat(page.content().get(0).items().get(0).aiAnalysis()).isNull();
        verify(aiAnalysisLookupService, org.mockito.Mockito.never()).findDetailById(any());
    }
}
