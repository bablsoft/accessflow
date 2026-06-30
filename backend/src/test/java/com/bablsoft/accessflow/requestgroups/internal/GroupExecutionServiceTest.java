package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService;
import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService.ApiInlineExecutionResult;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupExecutionServiceTest {

    @Mock
    private RequestGroupRepository groupRepository;
    @Mock
    private RequestGroupItemRepository itemRepository;
    @Mock
    private RequestGroupStateService stateService;
    @Mock
    private QueryParser queryParser;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private DatasourceLookupService datasourceLookupService;
    @Mock
    private DatasourceUserPermissionLookupService permissionLookupService;
    @Mock
    private MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock
    private RowSecurityResolutionService rowSecurityResolutionService;
    @Mock
    private ApiInlineExecutionService apiInlineExecutionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private GroupExecutionService service;

    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setStatus(RequestGroupStatus.APPROVED);
        org.mockito.Mockito.lenient().when(groupRepository.findById(group.getId()))
                .thenReturn(java.util.Optional.of(group));
        org.mockito.Mockito.lenient().when(itemRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private RequestGroupItemEntity apiItem(int order) {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(order);
        item.setTargetKind(RequestGroupTargetKind.API_CALL);
        item.setApiConnectorId(UUID.randomUUID());
        item.setVerb("GET");
        item.setRequestPath("/x");
        return item;
    }

    private ApiInlineExecutionResult ok() {
        return new ApiInlineExecutionResult(true, 200, 5, 10L, false, "{}", "application/json", null);
    }

    private ApiInlineExecutionResult fail() {
        return new ApiInlineExecutionResult(false, 500, 5, 0L, false, null, null, "boom");
    }

    @Test
    void stopsOnFirstFailureAndSkipsRemaining() {
        var items = new ArrayList<>(List.of(apiItem(0), apiItem(1), apiItem(2)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(items);
        when(apiInlineExecutionService.executeInline(any())).thenReturn(ok(), fail());

        service.execute(group.getId(), null, "manual");

        assertThat(items.get(0).getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
        assertThat(items.get(1).getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(items.get(2).getStatus()).isEqualTo(RequestGroupItemStatus.SKIPPED);
        verify(stateService).apply(group, RequestGroupStatus.EXECUTING);
        verify(stateService).apply(group, RequestGroupStatus.PARTIALLY_EXECUTED);
    }

    @Test
    void firstMemberFailureMakesGroupFailed() {
        var items = new ArrayList<>(List.of(apiItem(0), apiItem(1)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(items);
        when(apiInlineExecutionService.executeInline(any())).thenReturn(fail());

        service.execute(group.getId(), null, "manual");

        assertThat(items.get(0).getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(items.get(1).getStatus()).isEqualTo(RequestGroupItemStatus.SKIPPED);
        verify(stateService).apply(group, RequestGroupStatus.FAILED);
    }

    @Test
    void continueOnErrorRunsAllAndExecutesGroup() {
        group.setContinueOnError(true);
        var items = new ArrayList<>(List.of(apiItem(0), apiItem(1)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(items);
        when(apiInlineExecutionService.executeInline(any())).thenReturn(fail(), ok());

        service.execute(group.getId(), null, "manual");

        assertThat(items.get(0).getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(items.get(1).getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
        verify(stateService).apply(group, RequestGroupStatus.EXECUTED);
    }

    @Test
    void ignoresGroupNotApproved() {
        group.setStatus(RequestGroupStatus.DRAFT);
        service.execute(group.getId(), null, "manual");
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }
}
