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
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
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
import java.util.Map;
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
    @Mock private SqlReviewService sqlReviewService;
    @Mock private SqlReviewFindingService sqlReviewFindingService;
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
                apiConnectorAdminService, userQueryService, sqlReviewService,
                sqlReviewFindingService, auditLogService, eventPublisher, objectMapper);
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
                false, bg, List.of(), List.of(), List.of(), null, List.of(), List.of(), null, null);
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
    void submitRejectsAMemberThatReferencesADeniedColumn() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(deniedColumnItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingPerm(false)));
        stubDeniedColumnParse();

        assertThatThrownBy(() -> service.submit(new SubmitRequestGroupCommand(group.getId(), orgId,
                userId, false, false, null, "1.2.3.4", "ua")))
                .isInstanceOf(RequestGroupPermissionException.class);
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }

    @Test
    void breakGlassSubmitAlsoRejectsADeniedColumn() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(deniedColumnItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingPerm(true)));
        stubDeniedColumnParse();

        assertThatThrownBy(() -> service.submit(new SubmitRequestGroupCommand(group.getId(), orgId,
                userId, false, true, null, "1.2.3.4", "ua")))
                .isInstanceOf(RequestGroupPermissionException.class);
    }

    @Test
    void submitRejectsAMemberThatReferencesADeniedTable() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(deniedTableItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTablePerm(false)));
        stubDeniedTableParse();

        assertThatThrownBy(() -> service.submit(new SubmitRequestGroupCommand(group.getId(), orgId,
                userId, false, false, null, "1.2.3.4", "ua")))
                .isInstanceOf(RequestGroupPermissionException.class)
                .hasMessageContaining("crm.salary");
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }

    @Test
    void breakGlassSubmitAlsoRejectsADeniedTable() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(deniedTableItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTablePerm(true)));
        stubDeniedTableParse();

        assertThatThrownBy(() -> service.submit(new SubmitRequestGroupCommand(group.getId(), orgId,
                userId, false, true, null, "1.2.3.4", "ua")))
                .isInstanceOf(RequestGroupPermissionException.class)
                .hasMessageContaining("crm.salary");
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }

    private RequestGroupItemEntity deniedTableItem() {
        var item = new RequestGroupItemEntity();
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        item.setSqlText("SELECT * FROM crm.salary");
        return item;
    }

    private DatasourceUserPermissionView denyingTablePerm(boolean breakGlass) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true, false,
                false, breakGlass, List.of("crm"), List.of(), List.of(), List.of(), List.of(),
                List.of("crm.salary"), null, null);
    }

    private void stubDeniedTableParse() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT * FROM crm.salary"), java.util.Set.of("crm.salary"), false, false,
                java.util.Set.of(com.bablsoft.accessflow.core.api.ColumnReference.wildcard(
                        java.util.Set.of("crm.salary"))), true));
    }

    private RequestGroupItemEntity deniedColumnItem() {
        var item = new RequestGroupItemEntity();
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        item.setSqlText("SELECT ssn FROM customer");
        return item;
    }

    private DatasourceUserPermissionView denyingPerm(boolean breakGlass) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true, false,
                false, breakGlass, List.of(), List.of(), List.of(), List.of("customer.ssn"), List.of(), List.of(), null, null);
    }

    private void stubDeniedColumnParse() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT ssn FROM customer"), java.util.Set.of("customer"), false, false,
                java.util.Set.of(new com.bablsoft.accessflow.core.api.ColumnReference(
                        java.util.Set.of("customer"), "ssn")), true));
    }

    @Test
    void submitRejectsAMemberThatReferencesATableOutsideTheAllowList() {
        var group = stubAllowListSubmit(List.of(), List.of("public.customers"), false,
                "public.orders");

        assertThatThrownBy(() -> submit(group, false))
                .isInstanceOf(RequestGroupPermissionException.class)
                .hasMessageContaining("public.orders");
        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }

    @Test
    void submitAcceptsAMemberWhoseTablesAreCoveredByTableOrSchemaEntries() {
        var group = stubAllowListSubmit(List.of("sales"), List.of("public.customers"), false,
                "public.customers", "sales.orders");

        submit(group, false);

        verify(stateService).apply(group, RequestGroupStatus.PENDING_AI);
    }

    @Test
    void aBareAllowListEntryCoversOnlyAnUnqualifiedReference() {
        var covered = stubAllowListSubmit(List.of(), List.of("orders"), false, "orders");
        submit(covered, false);
        verify(stateService).apply(covered, RequestGroupStatus.PENDING_AI);

        var qualified = stubAllowListSubmit(List.of(), List.of("orders"), false, "public.orders");
        assertThatThrownBy(() -> submit(qualified, false))
                .isInstanceOf(RequestGroupPermissionException.class);
    }

    @Test
    void emptyAllowListsAreUnrestrictedAndSkipTheParse() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(allowListItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, false, false)));

        submit(group, false);

        verify(queryParser, org.mockito.Mockito.never()).parse(any(), any());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_AI);
    }

    @Test
    void breakGlassSubmitAlsoRejectsATableOutsideTheAllowList() {
        var group = stubAllowListSubmit(List.of("sales"), List.of(), true, "hr.salaries");

        assertThatThrownBy(() -> submit(group, true))
                .isInstanceOf(RequestGroupPermissionException.class)
                .hasMessageContaining("hr.salaries");
        verify(executionService, org.mockito.Mockito.never()).execute(any(), any(), any());
    }

    @Test
    void adminSubmitIsNotBoundByTheAllowList() {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(allowListItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(allowListPerm(List.of(), List.of("public.customers"), false)));

        service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, true, false, null,
                "1.2.3.4", "ua"));

        verify(queryParser, org.mockito.Mockito.never()).parse(any(), any());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_AI);
    }

    private RequestGroupEntity stubAllowListSubmit(List<String> schemas, List<String> tables,
                                                   boolean breakGlass, String... referenced) {
        var group = draftGroup();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(allowListItem()));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(allowListPerm(schemas, tables, breakGlass)));
        lenient().when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());
        when(queryParser.parse(any(), any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT 1"), java.util.Set.of(referenced)));
        return group;
    }

    private void submit(RequestGroupEntity group, boolean breakGlass) {
        service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, false, breakGlass,
                null, "1.2.3.4", "ua"));
    }

    private RequestGroupItemEntity allowListItem() {
        var item = new RequestGroupItemEntity();
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setQueryType(QueryType.SELECT);
        item.setSqlText("SELECT 1");
        return item;
    }

    private DatasourceUserPermissionView allowListPerm(List<String> schemas, List<String> tables,
                                                       boolean breakGlass) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true, false,
                false, breakGlass, schemas, tables, List.of(), List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void submitRecordsSqlReviewFindingsForEveryQueryMemberAndSkipsApiMembers() {
        var group = draftGroup();
        var query = new RequestGroupItemEntity();
        query.setId(UUID.randomUUID());
        query.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        query.setDatasourceId(datasourceId);
        query.setSqlText("SELECT * FROM t");
        query.setQueryType(QueryType.SELECT);
        var api = persistedApiItem();
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(List.of(query, api));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, false, false)));
        when(apiConnectorPermissionLookupService.findFor(connectorId, userId))
                .thenReturn(Optional.of(apiPerm(true, true, false)));
        var review = new SqlReviewResult(true, List.of(new SqlReviewFinding("select_star",
                SqlReviewSeverity.BLOCK, 0, 1, Map.of())));
        when(sqlReviewService.evaluate(orgId, datasourceId, "SELECT * FROM t")).thenReturn(review);

        service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, false,
                false, null, "1.2.3.4", "ua"));

        // The verdict is persisted, not acted on: routing happens in GroupAiAnalysisListener.
        verify(sqlReviewFindingService).recordForGroupItem(query.getId(), review);
        verify(sqlReviewFindingService, org.mockito.Mockito.never())
                .recordForGroupItem(org.mockito.ArgumentMatchers.eq(api.getId()), any());
        verify(stateService).apply(group, RequestGroupStatus.PENDING_AI);
    }

    @Test
    void breakGlassSubmitStillRecordsSqlReviewFindings() {
        var group = draftGroup();
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setTargetKind(com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setSqlText("DELETE FROM t");
        item.setQueryType(QueryType.DELETE);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId)).thenReturn(Optional.of(group));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId())).thenReturn(List.of(item));
        when(datasourcePermissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(dsPerm(true, true, true)));
        var review = new SqlReviewResult(true, List.of(new SqlReviewFinding("missing_where_on_delete",
                SqlReviewSeverity.BLOCK, 0, 1, Map.of())));
        when(sqlReviewService.evaluate(orgId, datasourceId, "DELETE FROM t")).thenReturn(review);

        service.submit(new SubmitRequestGroupCommand(group.getId(), orgId, userId, false, true, null,
                null, null));

        verify(sqlReviewFindingService).recordForGroupItem(item.getId(), review);
        verify(stateService).apply(group, RequestGroupStatus.APPROVED);
        verify(executionService).execute(group.getId(), userId, "break_glass");
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
