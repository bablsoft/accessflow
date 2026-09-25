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
    private com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    @Mock
    private ApiInlineExecutionService apiInlineExecutionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private com.bablsoft.accessflow.core.api.BytesScannedCapResolutionService bytesScannedCapResolutionService;
    @Mock
    private org.springframework.context.MessageSource messageSource;
    @Mock
    private com.bablsoft.accessflow.proxy.api.QueryCostEstimateService queryCostEstimateService;
    @Mock
    private com.bablsoft.accessflow.requestgroups.internal.persistence.repo.GroupReviewDecisionRepository decisionRepository;
    @Mock
    private com.bablsoft.accessflow.core.api.DataBudgetStatusService dataBudgetStatusService;
    @Mock
    private com.bablsoft.accessflow.core.api.DataBudgetUsageService dataBudgetUsageService;
    @InjectMocks
    private GroupExecutionService service;

    private RequestGroupEntity group;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(dataBudgetStatusService.statusFor(any(), any()))
                .thenAnswer(inv -> com.bablsoft.accessflow.core.api.DataBudgetStatus.none(
                        inv.getArgument(0)));
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

    private RequestGroupItemEntity queryItem() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        item.setQueryType(com.bablsoft.accessflow.core.api.QueryType.SELECT);
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(new ArrayList<>(List.of(item)));
        when(permissionLookupService.findFor(any(), any())).thenReturn(java.util.Optional.empty());
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(datasourceLookupService.findById(any())).thenReturn(java.util.Optional.empty());
        when(queryParser.parse(any(), any()))
                .thenReturn(new com.bablsoft.accessflow.core.api.SqlParseResult(
                        com.bablsoft.accessflow.core.api.QueryType.SELECT, "SELECT 1"));
        return item;
    }

    private void givenBytesCap(RequestGroupItemEntity item,
                               com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction missing) {
        when(bytesScannedCapResolutionService.resolve(item.getDatasourceId(), group.getSubmittedBy()))
                .thenReturn(java.util.Optional.of(new com.bablsoft.accessflow.core.api.AppliedBytesCap(
                        1_000L, com.bablsoft.accessflow.core.api.BytesScannedCapSource.DATASOURCE,
                        missing)));
    }

    @Test
    void aQueryMemberOverTheBytesCapFailsWithoutRunning() {
        var item = queryItem();
        givenBytesCap(item, com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        when(queryCostEstimateService.estimateBytesScanned(any()))
                .thenReturn(java.util.Optional.of(5_000L));
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.eq("error.bytes_cap.exceeded"),
                any(), any())).thenReturn("over the cap");

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("over the cap");
        verify(queryExecutor, org.mockito.Mockito.never()).execute(any());
        var audit = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(audit.capture());
        assertThat(audit.getAllValues()).anySatisfy(entry -> {
            assertThat(entry.action())
                    .isEqualTo(com.bablsoft.accessflow.audit.api.AuditAction.QUERY_BYTES_SCANNED_CAP_ENFORCED);
            assertThat(entry.metadata()).containsEntry("estimated_bytes", 5_000L)
                    .containsEntry("item_id", item.getId().toString());
        });
    }

    @Test
    void aQueryMemberWithoutAnEstimateFailsUnderReject() {
        var item = queryItem();
        givenBytesCap(item, com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction.REJECT);
        when(queryCostEstimateService.estimateBytesScanned(any()))
                .thenReturn(java.util.Optional.empty());
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.eq("error.bytes_cap.no_estimate"),
                any(), any())).thenReturn("no estimate");

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("no estimate");
    }

    @Test
    void aQueryMemberWithoutAnEstimateRunsUnderRequireReviewOnceAPersonApproved() {
        var item = queryItem();
        givenBytesCap(item, com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        when(queryCostEstimateService.estimateBytesScanned(any()))
                .thenReturn(java.util.Optional.empty());
        when(decisionRepository.existsByRequestGroupIdAndDecision(group.getId(),
                com.bablsoft.accessflow.core.api.DecisionType.APPROVED)).thenReturn(true);
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 1L, false, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
    }

    @Test
    void aQueryMemberWithoutAnEstimateIsRefusedWhenNoPersonApprovedTheGroup() {
        var item = queryItem();
        givenBytesCap(item, com.bablsoft.accessflow.core.api.BytesCapMissingEstimateAction.REQUIRE_REVIEW);
        when(queryCostEstimateService.estimateBytesScanned(any()))
                .thenReturn(java.util.Optional.empty());
        when(messageSource.getMessage(
                org.mockito.ArgumentMatchers.eq("error.bytes_cap.no_estimate_unreviewed"), any(),
                any())).thenReturn("never reviewed");

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("never reviewed");
        verify(queryExecutor, org.mockito.Mockito.never()).execute(any());
    }

    private void givenBudget(RequestGroupItemEntity item,
                             com.bablsoft.accessflow.core.api.DataBudgetBreachAction action,
                             long usedRows) {
        when(dataBudgetStatusService.statusFor(item.getDatasourceId(), group.getSubmittedBy()))
                .thenReturn(new com.bablsoft.accessflow.core.api.DataBudgetStatus(
                        item.getDatasourceId(), "ds", List.of(
                                new com.bablsoft.accessflow.core.api.DataBudgetConsumption(
                                        UUID.randomUUID(), "Daily", 100L, null, 60, action, null,
                                        usedRows, 0))));
    }

    @Test
    void aQueryMemberIsCappedToTheRemainingAllowanceAndCharged() {
        var item = queryItem();
        givenBudget(item, com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, 95);
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 5L, true, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
        var request = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryExecutionRequest.class);
        verify(queryExecutor).execute(request.capture());
        assertThat(request.getValue().maxRowsOverride()).isEqualTo(5);
        var usage = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.DataBudgetUsageRecord.class);
        verify(dataBudgetUsageService).record(usage.capture());
        assertThat(usage.getValue().requestGroupId()).isEqualTo(group.getId());
        assertThat(usage.getValue().source())
                .isEqualTo(com.bablsoft.accessflow.core.api.DataBudgetUsageSource.REQUEST_GROUP);
    }

    @Test
    void anExhaustedRejectBudgetFailsTheMemberWithoutRunning() {
        var item = queryItem();
        givenBudget(item, com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, 100);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.eq("error.data_budget.exhausted"),
                any(), any())).thenReturn("used up");

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        assertThat(item.getErrorMessage()).isEqualTo("used up");
        verify(queryExecutor, org.mockito.Mockito.never()).execute(any());
        var audit = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(audit.capture());
        assertThat(audit.getAllValues()).anySatisfy(entry -> {
            assertThat(entry.action())
                    .isEqualTo(com.bablsoft.accessflow.audit.api.AuditAction.QUERY_DATA_BUDGET_ENFORCED);
            assertThat(entry.metadata()).containsEntry("item_id", item.getId().toString())
                    .containsEntry("window_minutes", 60);
        });
    }

    @Test
    void anExhaustedReviewBudgetFailsTheMemberEvenOnceAPersonApprovedTheGroup() {
        var item = queryItem();
        givenBudget(item, com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REQUIRE_REVIEW, 100);
        // A person approved the group — which, fail-closed, must not lift an exhausted budget.
        org.mockito.Mockito.lenient().when(decisionRepository.existsByRequestGroupIdAndDecision(group.getId(),
                com.bablsoft.accessflow.core.api.DecisionType.APPROVED)).thenReturn(true);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.eq("error.data_budget.exhausted"),
                any(), any())).thenReturn("used up");

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.FAILED);
        verify(queryExecutor, org.mockito.Mockito.never()).execute(any());
    }

    @Test
    void aFailedUsageWriteNeverFailsTheMember() {
        var item = queryItem();
        givenBudget(item, com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, 0);
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 1L, false, java.time.Duration.ofMillis(3)));
        org.mockito.Mockito.doThrow(new IllegalStateException("ledger down"))
                .when(dataBudgetUsageService).record(any());

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
    }

    @Test
    void anUncappedQueryMemberIsNeverDryRun() {
        queryItem();
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 1L, false, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        verify(queryCostEstimateService, org.mockito.Mockito.never()).estimateBytesScanned(any());
    }

    @Test
    void runsQueryMemberThroughProxyExecutor() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        item.setQueryType(com.bablsoft.accessflow.core.api.QueryType.SELECT);
        var items = new ArrayList<>(List.of(item));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(items);
        when(permissionLookupService.findFor(any(), any())).thenReturn(java.util.Optional.empty());
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(datasourceLookupService.findById(any())).thenReturn(java.util.Optional.empty());
        when(queryParser.parse(any(), any()))
                .thenReturn(new com.bablsoft.accessflow.core.api.SqlParseResult(
                        com.bablsoft.accessflow.core.api.QueryType.SELECT, "SELECT 1"));
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 7L, false, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        assertThat(item.getStatus()).isEqualTo(RequestGroupItemStatus.EXECUTED);
        assertThat(item.getRowsAffected()).isEqualTo(7L);
        verify(stateService).apply(group, RequestGroupStatus.EXECUTED);
    }

    @Test
    void queryMemberHonoursTheSubmittersRowLimitOverride() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        item.setQueryType(com.bablsoft.accessflow.core.api.QueryType.SELECT);
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(new ArrayList<>(List.of(item)));
        when(permissionLookupService.findFor(group.getSubmittedBy(), item.getDatasourceId()))
                .thenReturn(java.util.Optional.of(
                        new com.bablsoft.accessflow.core.api.DatasourceUserPermissionView(
                                UUID.randomUUID(), group.getSubmittedBy(), item.getDatasourceId(),
                                true, false, false, false, List.of(), List.of(),
                                List.of("public.users.ssn"), null, List.of(), List.of(), List.of(), 100, null)));
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(datasourceLookupService.findById(any())).thenReturn(java.util.Optional.empty());
        when(queryParser.parse(any(), any()))
                .thenReturn(new com.bablsoft.accessflow.core.api.SqlParseResult(
                        com.bablsoft.accessflow.core.api.QueryType.SELECT, "SELECT 1"));
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 100L, true, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryExecutionRequest.class);
        verify(queryExecutor).execute(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(100);
        assertThat(captor.getValue().restrictedColumns()).containsExactly("public.users.ssn");
    }

    @Test
    void queryMemberTightensTheOverrideByRowLimitPolicies() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        item.setQueryType(com.bablsoft.accessflow.core.api.QueryType.SELECT);
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(new ArrayList<>(List.of(item)));
        when(permissionLookupService.findFor(group.getSubmittedBy(), item.getDatasourceId()))
                .thenReturn(java.util.Optional.of(
                        new com.bablsoft.accessflow.core.api.DatasourceUserPermissionView(
                                UUID.randomUUID(), group.getSubmittedBy(), item.getDatasourceId(),
                                true, false, false, false, List.of(), List.of(),
                                List.of("public.users.ssn"), null, List.of(), List.of(), List.of(), 100, null)));
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(datasourceLookupService.findById(any())).thenReturn(java.util.Optional.empty());
        when(queryParser.parse(any(), any()))
                .thenReturn(new com.bablsoft.accessflow.core.api.SqlParseResult(
                        com.bablsoft.accessflow.core.api.QueryType.SELECT, "SELECT 1"));
        when(rowLimitPolicyResolutionService.resolve(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(new com.bablsoft.accessflow.core.api.AppliedRowLimit(
                        30, java.util.Set.of(UUID.randomUUID()))));
        when(queryExecutor.execute(any())).thenReturn(
                new com.bablsoft.accessflow.core.api.SelectExecutionResult(
                        List.of(), List.of(), 100L, true, java.time.Duration.ofMillis(3)));

        service.execute(group.getId(), null, "manual");

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryExecutionRequest.class);
        verify(queryExecutor).execute(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(30);
        assertThat(captor.getValue().restrictedColumns()).containsExactly("public.users.ssn");
    }
}
