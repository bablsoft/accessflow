package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupValidationException;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRequestGroupServiceTest {

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

    private DefaultRequestGroupService newService() {
        return new DefaultRequestGroupService(groupRepository, itemRepository, stateService,
                executionService, queryParser, datasourceLookupService, datasourcePermissionLookupService,
                apiConnectorPermissionLookupService, apiConnectorAdminService, userQueryService,
                auditLogService, eventPublisher, objectMapper);
    }

    private RequestGroupItemInput queryInput() {
        return new RequestGroupItemInput(
                com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY, 0,
                datasourceId, "SELECT 1", false, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private DatasourceUserPermissionView perm(boolean read, boolean breakGlass) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, read, false,
                false, breakGlass, List.of(), List.of(), List.of(), null);
    }

    @Test
    void createDraftRejectsEmptyGroup() {
        service = newService();
        var command = new CreateRequestGroupCommand(orgId, userId, false, "g", null, false, List.of());
        assertThatThrownBy(() -> service.createDraft(command))
                .isInstanceOf(RequestGroupValidationException.class);
    }

    @Test
    void createDraftRejectsMemberWithoutPermission() {
        service = newService();
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(perm(false, false)));

        var command = new CreateRequestGroupCommand(orgId, userId, false, "g", null, false,
                List.of(queryInput()));
        assertThatThrownBy(() -> service.createDraft(command))
                .isInstanceOf(RequestGroupPermissionException.class);
    }

    @Test
    void breakGlassSubmitRequiresCanBreakGlassOnEveryTarget() {
        service = newService();
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(orgId);
        group.setSubmittedBy(userId);
        group.setStatus(RequestGroupStatus.DRAFT);
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(item));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(perm(true, false))); // can read, but NOT break-glass

        var command = new SubmitRequestGroupCommand(group.getId(), orgId, userId, false, true, null,
                null, null);
        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(RequestGroupPermissionException.class);
    }
}
