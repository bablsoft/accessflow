package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.QuotaType;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.RowSecurityClassificationService;
import com.bablsoft.accessflow.workflow.api.AccessSimulationInput;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibility;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibilityService;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.DecisionStepKind;
import com.bablsoft.accessflow.workflow.api.DecisionTrace;
import com.bablsoft.accessflow.workflow.api.DecisionTraceStep;
import com.bablsoft.accessflow.workflow.api.StepOutcome;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultAccessSimulationServiceTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock UserQueryService userQueryService;
    @Mock QuotaService quotaService;
    @Mock QueryParser queryParser;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock QueryDecisionEvaluator queryDecisionEvaluator;
    @Mock RoutingPolicyEngine routingPolicyEngine;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock RowSecurityResolutionService rowSecurityResolutionService;
    @Mock RowSecurityClassificationService rowSecurityClassificationService;
    @Mock MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock BreakGlassEligibilityService breakGlassEligibilityService;

    private DefaultAccessSimulationService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void buildService() {
        service = new DefaultAccessSimulationService(datasourceAdminService, userQueryService,
                quotaService, queryParser, permissionLookupService,
                rolePermissionHolderLookupService, queryDecisionEvaluator, routingPolicyEngine,
                reviewPlanLookupService, reviewerEligibilityService, rowSecurityResolutionService,
                rowSecurityClassificationService, maskingPolicyResolutionService,
                breakGlassEligibilityService, clock);
    }

    @BeforeEach
    void stubHappyPath() {
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user()));
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasource(true));
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasource(true));
        when(queryParser.parse(any(), any())).thenReturn(
                new SqlParseResult(QueryType.SELECT, false, List.of("SELECT 1"),
                        Set.of("public.payments"), true, false));
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of());
        when(permissionLookupService.findContributions(userId, datasourceId))
                .thenReturn(List.of(contribution()));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, false, false, List.of("public"))));
        when(queryDecisionEvaluator.evaluate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any())).thenReturn(planDecision(QueryStatus.PENDING_REVIEW));
        when(routingPolicyEngine.enabledFor(organizationId, datasourceId)).thenReturn(List.of());
        when(routingPolicyEngine.evaluateAll(any(), any())).thenReturn(List.of());
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.of(plan()));
        when(rowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of());
        when(maskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of());
        when(breakGlassEligibilityService.findEligible(userId, organizationId))
                .thenReturn(List.of());
    }

    // ── The non-negotiable guarantee ──────────────────────────────────────────

    @Test
    void theSimulatorHasNoWayToCauseASideEffect() {
        // Structural, not behavioural: if a future change wires a persistence service, an event
        // publisher, an AI analyzer or a notification dispatcher into this class, the guarantee in
        // its javadoc quietly stops being true. This is what makes it stay true.
        var forbidden = List.of("Persistence", "StateService", "ApplicationEventPublisher",
                "EventPublisher", "AiAnalyzer", "Notification", "Dispatcher", "DataSource",
                "JdbcTemplate", "Connection", "QueryProxy", "QueryExecution");
        var declared = java.util.Arrays.stream(DefaultAccessSimulationService.class
                        .getDeclaredFields())
                .map(f -> f.getType().getSimpleName())
                .toList();

        assertThat(declared).allSatisfy(type ->
                assertThat(forbidden).noneSatisfy(bad -> assertThat(type).contains(bad)));
    }

    @Test
    void aSimulationNeverAsksTheEvaluatorToApplyAnything() {
        service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        verify(queryDecisionEvaluator).evaluate(any(), eq(AiOutcome.COMPLETED), eq(RiskLevel.LOW),
                eq(5), eq(clock));
        verify(datasourceAdminService, never()).update(any(), any(), any());
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    void everyStageIsReportedInOrderOnTheHappyPath() {
        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) DecisionStepKind.values());
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void everyStageIsStillReportedWhenTheRequestIsBlockedEarly() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) DecisionStepKind.values());
        assertThat(result.resultingStatus()).isNull();
        assertThat(step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION).outcome())
                .isEqualTo(StepOutcome.DENY);
        assertThat(step(result.steps(), DecisionStepKind.ROUTING_POLICIES).outcome())
                .isEqualTo(StepOutcome.SKIP);
        verify(queryDecisionEvaluator, never()).evaluate(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void everySimulationReportsTheApproximationsItMade() {
        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(result.caveats()).contains(SimulationCaveat.CLIENT_CONTEXT_ABSENT,
                SimulationCaveat.COST_ESTIMATE_ABSENT);
    }

    // ── Lookup failures ───────────────────────────────────────────────────────

    @Test
    void aUserInAnotherOrganizationIsNotFound() {
        when(userQueryService.findById(userId)).thenReturn(Optional.of(
                new UserView(userId, "x@y.io", "X", UserRoleType.ANALYST, null, "ANALYST",
                        UUID.randomUUID(), true, AuthProviderType.LOCAL, null, null, "en", false,
                        false, Instant.now(), null, Instant.now())));

        assertThatThrownBy(() -> service.simulate(organizationId,
                input(AiOutcome.COMPLETED, RiskLevel.LOW, 5)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void aDatasourceInvisibleToTheSimulatedUserIsADenialNotAnError() {
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var gate = step(result.steps(), DecisionStepKind.DATASOURCE_GATES);
        assertThat(gate.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(gate.details()).containsEntry("visible_to_user", false);
        assertThat(result.resultingStatus()).isNull();
    }

    @Test
    void anInactiveDatasourceStopsTheRequest() {
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasource(false));
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasource(false));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.DATASOURCE_GATES).outcome())
                .isEqualTo(StepOutcome.DENY);
    }

    // ── Individual stages ─────────────────────────────────────────────────────

    @Test
    void anExceededQuotaStopsTheRequestAndReportsTheNumbers() {
        org.mockito.Mockito.doThrow(new QuotaExceededException(QuotaType.QUERIES_PER_DAY, organizationId,
                100, 100L)).when(quotaService).checkQueryQuota(organizationId);

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var quota = step(result.steps(), DecisionStepKind.QUOTA);
        assertThat(quota.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(quota.details()).containsEntry("limit", 100).containsEntry("current", 100L);
        assertThat(result.resultingStatus()).isNull();
    }

    @Test
    void unparseableSqlStopsTheRequest() {
        when(queryParser.parse(any(), any())).thenThrow(new InvalidSqlException("nope"));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.SQL_PARSE).outcome())
                .isEqualTo(StepOutcome.DENY);
    }

    @Test
    void anUnsupportedStatementTypeStopsTheRequest() {
        when(queryParser.parse(any(), any())).thenReturn(
                new SqlParseResult(QueryType.OTHER, "GRANT ALL"));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var parse = step(result.steps(), DecisionStepKind.SQL_PARSE);
        assertThat(parse.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(parse.details()).containsEntry("query_type", "OTHER");
    }

    @Test
    void aQueryAdminHolderPassesThePermissionGateWithNoPermissionRowAtAll() {
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of(userId));
        when(permissionLookupService.findContributions(userId, datasourceId)).thenReturn(List.of());
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var permission = step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION);
        assertThat(permission.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(permission.details()).containsEntry("query_admin_short_circuit", true);
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void thePermissionStepNamesEveryContributingGrant() {
        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var permission = step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION);
        assertThat(permission.outcome()).isEqualTo(StepOutcome.ALLOW);
        @SuppressWarnings("unchecked")
        var grants = (List<Map<String, Object>>) permission.details().get("contributing_grants");
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0)).containsEntry("group_name", "payments-oncall");
    }

    @Test
    void aMissingCapabilityStopsTheRequest() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, true, false, List.of("public"))));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION).outcome())
                .isEqualTo(StepOutcome.DENY);
    }

    @Test
    void aTableOutsideTheAllowListStopsTheRequestAndNamesIt() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, false, false, List.of("reporting"))));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var permission = step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION);
        assertThat(permission.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(permission.details()).containsEntry("rejected_tables", List.of("public.payments"));
    }

    @Test
    void anAllowListPassOverNoTablesIsFlaggedAsVacuous() {
        // rejectedTables() returns empty for an empty table set, in the simulator exactly as in the
        // gate. Reporting it as a plain ALLOW would read as a verdict the check never actually made.
        when(queryParser.parse(any(), any())).thenReturn(
                new SqlParseResult(QueryType.SELECT, false, List.of("SELECT 1"), Set.of(), false,
                        false));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.SQL_PARSE).details())
                .containsEntry("referenced_tables", List.of());
        var permission = step(result.steps(), DecisionStepKind.EFFECTIVE_PERMISSION);
        assertThat(permission.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(permission.reasonKey())
                .isEqualTo("workflow.access_simulation.permission.allowed_no_tables");
        assertThat(permission.details().get("rejected_tables")).isEqualTo(List.of());
    }

    @Test
    void theRoutingStepCarriesEveryPolicyNotJustTheWinner() {
        var matched = policy("Escalate payment writes", 10, true, true);
        var missed = policy("Block payroll deletes", 5, false, false);
        when(routingPolicyEngine.evaluateAll(any(), any())).thenReturn(List.of(missed, matched));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var routing = step(result.steps(), DecisionStepKind.ROUTING_POLICIES);
        @SuppressWarnings("unchecked")
        var policies = (List<Map<String, Object>>) routing.details().get("policies");
        assertThat(policies).hasSize(2);
        assertThat(policies.get(0)).containsEntry("name", "Block payroll deletes")
                .containsEntry("matched", false);
        assertThat(policies.get(1)).containsEntry("matched", true).containsEntry("decisive", true);
    }

    @Test
    void reviewersAreSkippedWhenTheRequestWouldNotReachReview() {
        when(queryDecisionEvaluator.evaluate(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(planDecision(QueryStatus.APPROVED));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.ELIGIBLE_REVIEWERS).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void theSubmitterIsExcludedFromTheEligibleReviewers() {
        var other = UUID.randomUUID();
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(userId, other)));
        when(userQueryService.findByIds(List.of(other))).thenReturn(List.of(user(other)));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var reviewers = step(result.steps(), DecisionStepKind.ELIGIBLE_REVIEWERS);
        assertThat(reviewers.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(reviewers.details()).containsEntry("submitter_excluded", true);
        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) reviewers.details().get("reviewers");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).containsEntry("user_id", other);
    }

    @Test
    void anOnlySelfReviewerSetIsADenial() {
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(userId)));
        when(userQueryService.findByIds(List.of())).thenReturn(List.of());

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.ELIGIBLE_REVIEWERS).outcome())
                .isEqualTo(StepOutcome.DENY);
    }

    @Test
    void rowSecurityIsClassifiedOfflineAndReported() {
        when(rowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(predicate()));
        when(rowSecurityClassificationService.classify(eq(datasourceId), eq(DbType.POSTGRESQL),
                any(), any())).thenReturn(RowSecurityClassification.applied("jdbc",
                Set.of(UUID.randomUUID())));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var rowSecurity = step(result.steps(), DecisionStepKind.ROW_SECURITY);
        assertThat(rowSecurity.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(rowSecurity.details()).containsEntry("row_security_outcome", "APPLIED");
    }

    @Test
    void anUnclassifiableEngineIsACaveatNotAnAllClear() {
        when(rowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(predicate()));
        when(rowSecurityClassificationService.classify(any(), any(), any(), any()))
                .thenReturn(RowSecurityClassification.unknown("cassandra"));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(result.caveats())
                .contains(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE);
        assertThat(step(result.steps(), DecisionStepKind.ROW_SECURITY).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void maskingIsFilteredToTheReferencedTables() {
        when(maskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(
                        mask("public.payments.card_number"),
                        mask("public.employees.salary")));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var masking = step(result.steps(), DecisionStepKind.MASKING);
        assertThat(masking.outcome()).isEqualTo(StepOutcome.MATCH);
        @SuppressWarnings("unchecked")
        var policies = (List<Map<String, Object>>) masking.details().get("policies");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0)).containsEntry("column_ref", "public.payments.card_number");
    }

    @Test
    void aBareColumnNameMaskIsReportedWithItsCaveat() {
        when(maskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(mask("ssn")));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(result.caveats()).contains(SimulationCaveat.COLUMN_MATCH_BARE_NAME);
        @SuppressWarnings("unchecked")
        var policies = (List<Map<String, Object>>) step(result.steps(), DecisionStepKind.MASKING)
                .details().get("policies");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0)).containsEntry("bare_column_name", true);
    }

    @Test
    void breakGlassEligibilityIsReportedForThisDatasourceOnly() {
        when(breakGlassEligibilityService.findEligible(userId, organizationId)).thenReturn(
                List.of(new BreakGlassEligibility(UUID.randomUUID(), null)));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        var breakGlass = step(result.steps(), DecisionStepKind.BREAK_GLASS);
        assertThat(breakGlass.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(breakGlass.details()).containsEntry("can_break_glass", false);
    }

    @Test
    void aBreakGlassHolderIsReportedAsEligible() {
        when(breakGlassEligibilityService.findEligible(userId, organizationId)).thenReturn(
                List.of(new BreakGlassEligibility(datasourceId, null)));

        var result = service.simulate(organizationId, input(AiOutcome.COMPLETED, RiskLevel.LOW, 5));

        assertThat(step(result.steps(), DecisionStepKind.BREAK_GLASS).outcome())
                .isEqualTo(StepOutcome.ALLOW);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static DecisionTraceStep step(List<DecisionTraceStep> steps, DecisionStepKind kind) {
        return steps.stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    private AccessSimulationInput input(AiOutcome outcome, RiskLevel level, Integer score) {
        return new AccessSimulationInput(userId, datasourceId,
                "SELECT card_number FROM public.payments", outcome, level, score);
    }

    private QueryDecision planDecision(QueryStatus status) {
        var steps = List.of(
                DecisionTraceStep.of(DecisionStepKind.ROUTING_POLICIES, StepOutcome.NO_MATCH, "k"),
                DecisionTraceStep.of(DecisionStepKind.GRANT_FAST_PATH, StepOutcome.NO_MATCH, "k"),
                DecisionTraceStep.of(DecisionStepKind.REVIEW_PLAN, StepOutcome.DENY, "k"));
        return new QueryDecision(QueryDecisionKind.PLAN_PENDING_REVIEW, status, null, null, null,
                null, context(), new DecisionTrace(steps, status));
    }

    /** The evaluator only attaches a policy list when routing actually ran, i.e. a context exists. */
    private ConditionContext context() {
        return new ConditionContext(QueryType.SELECT, Set.of("public.payments"), RiskLevel.LOW, 5,
                "ANALYST", Set.of(), LocalDateTime.now(clock), true, false, false, null, null,
                false, null, false, null, null);
    }

    private RoutingPolicyEngine.PolicyEvaluation policy(String name, int priority, boolean matched,
                                                        boolean decisive) {
        var evaluable = new com.bablsoft.accessflow.workflow.internal.routing.EvaluablePolicy(
                UUID.randomUUID(), name, priority,
                com.bablsoft.accessflow.workflow.api.RoutingAction.ESCALATE, 1, "reason", null,
                false);
        return new RoutingPolicyEngine.PolicyEvaluation(evaluable, matched, decisive);
    }

    private UserView user() {
        return user(userId);
    }

    private UserView user(UUID id) {
        return new UserView(id, id + "@example.com", "User", UserRoleType.ANALYST, null, "ANALYST",
                organizationId, true, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
    }

    private DatasourceView datasource(boolean active) {
        return new DatasourceView(datasourceId, organizationId, "prod", DbType.POSTGRESQL,
                "db.invalid", 5432, "app", "u", SslMode.DISABLE, 5, 1000, false, true, null, true,
                null, false, null, null, null, List.of(), active, Instant.now(), null, false, null);
    }

    private DatasourcePermissionContribution contribution() {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.GROUP,
                UUID.randomUUID(), userId, datasourceId, UUID.randomUUID(), "payments-oncall",
                true, false, false, false, List.of("public"), List.of(), List.of(), null);
    }

    private DatasourceUserPermissionView permission(boolean canRead, boolean canWrite,
                                                    boolean canDdl, List<String> allowedSchemas) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, canRead,
                canWrite, canDdl, false, allowedSchemas, List.of(), List.of(), null);
    }

    private ReviewPlanSnapshot plan() {
        return new ReviewPlanSnapshot(UUID.randomUUID(), organizationId, true, true, 1, false, 1,
                List.of(new ApproverRule(null, "REVIEWER", 1)), List.of());
    }

    private ResolvedRowSecurityPredicate predicate() {
        return new ResolvedRowSecurityPredicate(UUID.randomUUID(), "public.payments", "tenant_id",
                RowSecurityOperator.EQUALS, List.of("t1"));
    }

    private ResolvedColumnMask mask(String columnRef) {
        return new ResolvedColumnMask(UUID.randomUUID(), columnRef, MaskingStrategy.PARTIAL,
                Map.of());
    }
}
