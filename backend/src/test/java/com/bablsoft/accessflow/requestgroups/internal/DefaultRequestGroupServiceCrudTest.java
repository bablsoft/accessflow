package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService.ApiConnectorPermissionLookupView;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
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
                executionService, queryParser, datasourceLookupService, datasourcePermissionLookupService,
                apiConnectorPermissionLookupService, apiConnectorAdminService, userQueryService,
                auditLogService, eventPublisher, objectMapper);
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
        return new ApiConnectorPermissionLookupView(connectorId, userId, read, write, bg, List.of(), null);
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
}
