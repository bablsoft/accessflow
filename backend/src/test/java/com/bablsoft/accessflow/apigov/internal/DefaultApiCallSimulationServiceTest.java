package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationInput;
import com.bablsoft.accessflow.apigov.api.ApiConnectorGovernanceView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiCallSimulationServiceTest {

    @Mock private ApiConnectorLookupService connectorLookupService;
    @Mock private ApiSchemaService schemaService;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private ApiDecisionEvaluator decisionEvaluator;
    @Mock private ApiRoutingPolicyEngine routingEngine;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private ApiConnectorMaskingResolutionService maskingResolutionService;
    @Mock private RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock private UserQueryService userQueryService;

    private DefaultApiCallSimulationService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiCallSimulationService(connectorLookupService, schemaService,
                permissionResolver, decisionEvaluator, routingEngine, reviewPlanLookupService,
                maskingResolutionService, rolePermissionHolderLookupService, userQueryService);

        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(userId, true)));
        when(userQueryService.findByIds(any())).thenReturn(List.of(user(reviewerId, true)));
        when(connectorLookupService.findGovernanceView(connectorId, organizationId))
                .thenReturn(Optional.of(connector(true, null)));
        when(schemaService.listOperations(connectorId, organizationId))
                .thenReturn(List.of(operation("deleteCustomer", "DELETE", true)));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, List.of(), false)));
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(any(), any()))
                .thenReturn(List.of());
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.API_REQUEST_REVIEW)).thenReturn(List.of(reviewerId));
        when(decisionEvaluator.evaluate(any()))
                .thenReturn(decision(QueryStatus.PENDING_REVIEW));
        when(routingEngine.evaluateAll(any(), any(), any())).thenReturn(List.of());
        when(maskingResolutionService.resolveApplicable(any(), any(), any())).thenReturn(List.of());
        when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UserView user(UUID id, boolean active) {
        return user(id, active, organizationId);
    }

    private static UserView user(UUID id, boolean active, UUID organization) {
        return new UserView(id, id + "@example.com", "Dana", UserRoleType.ANALYST, null, "ANALYST",
                organization, active, AuthProviderType.LOCAL, null, null, "en", false, false, null,
                null, null);
    }

    private ApiConnectorGovernanceView connector(boolean active, UUID planReference) {
        return new ApiConnectorGovernanceView(connectorId, organizationId, "billing-api",
                ApiProtocol.REST, active, true, planReference, false, true);
    }

    private static ApiOperation operation(String id, String verb, boolean write) {
        return new ApiOperation(id, verb, "/customers", "", write, null, null);
    }

    private ResolvedApiConnectorPermission permission(boolean read, boolean write,
                                                      List<String> allowedOperations,
                                                      boolean breakGlass) {
        return new ResolvedApiConnectorPermission(connectorId, userId, read, write, breakGlass,
                false, allowedOperations, List.of("customer.ssn"), null);
    }

    private static ApiDecision decision(QueryStatus status) {
        var steps = List.of(
                DecisionTraceStep.of(ApiDecisionStepKind.ROUTING_POLICIES, StepOutcome.NO_MATCH,
                        "apigov.decision.routing.no_match"),
                DecisionTraceStep.of(ApiDecisionStepKind.REVIEW_REQUIREMENT,
                        status == QueryStatus.APPROVED ? StepOutcome.ALLOW : StepOutcome.DENY,
                        "apigov.decision.review.required_write"));
        return new ApiDecision(ApiDecisionKind.CONNECTOR_PENDING_REVIEW, status, null, 1,
                new DecisionTrace(steps, status));
    }

    private ApiCallSimulationInput input(String operationId, String verb) {
        return new ApiCallSimulationInput(userId, connectorId, operationId, verb,
                AiOutcome.COMPLETED, RiskLevel.HIGH);
    }

    private static DecisionTraceStep step(List<DecisionTraceStep> steps, ApiDecisionStepKind kind) {
        return steps.stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    // ── The non-negotiable guarantee ──────────────────────────────────────────

    @Test
    void theSimulatorHasNoWayToCauseASideEffect() {
        // Structural, not behavioural: if a future change wires a repository, a state service, an
        // event publisher, an analyzer or — the one specific to this module — anything that can
        // reach the governed third-party API into this class, the guarantee in its javadoc quietly
        // stops being true. This is what makes it stay true.
        var forbidden = List.of("Persistence", "Repository", "StateService",
                "ApplicationEventPublisher", "EventPublisher", "Analyzer", "Notification",
                "Dispatcher", "ExecutionService", "Executor", "RestClient", "HttpClient", "Prober",
                "Inserter", "AuditWriter", "Masker", "DataSource", "JdbcTemplate", "Connection");
        var declared = Arrays.stream(DefaultApiCallSimulationService.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(f -> f.getType().getSimpleName())
                .toList();

        assertThat(declared).isNotEmpty();
        assertThat(declared).allSatisfy(type ->
                assertThat(forbidden).noneSatisfy(bad -> assertThat(type).contains(bad)));
    }

    @Test
    void aSimulationNeverAsksTheEvaluatorToApplyAnything() {
        service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        verify(decisionEvaluator).evaluate(any());
        // The engine is consulted only through evaluateAll, which is the presentational read; the
        // live first-match short-circuit belongs to the evaluator.
        verify(routingEngine, never()).evaluate(any(), any(), any());
    }

    // ── Scoping ───────────────────────────────────────────────────────────────

    @Test
    void aUserInAnotherOrganizationIsNotFound() {
        when(userQueryService.findById(userId))
                .thenReturn(Optional.of(user(userId, true, UUID.randomUUID())));

        assertThatThrownBy(() -> service.simulate(organizationId, input("deleteCustomer", "DELETE")))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void aConnectorInAnotherOrganizationIsNotFound() {
        when(connectorLookupService.findGovernanceView(connectorId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(organizationId, input("deleteCustomer", "DELETE")))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    void everyStageIsReportedInOrderOnTheHappyPath() {
        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) ApiDecisionStepKind.values());
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(result.caveats()).containsExactly(SimulationCaveat.RESPONSE_SHAPE_ABSENT);
    }

    @Test
    void everyStageIsStillReportedWhenTheRequestIsBlockedEarly() {
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) ApiDecisionStepKind.values());
        assertThat(result.resultingStatus()).isNull();
        assertThat(step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION).outcome())
                .isEqualTo(StepOutcome.DENY);
        assertThat(step(result.steps(), ApiDecisionStepKind.ROUTING_POLICIES))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.SKIP);
                    assertThat(s.reasonKey()).isEqualTo("apigov.simulation.not_reached");
                });
        verify(decisionEvaluator, never()).evaluate(any());
    }

    // ── 1. Connector gates ────────────────────────────────────────────────────

    @Test
    void anInactiveConnectorBlocksTheCall() {
        when(connectorLookupService.findGovernanceView(connectorId, organizationId))
                .thenReturn(Optional.of(connector(false, null)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var gate = step(result.steps(), ApiDecisionStepKind.CONNECTOR_GATES);
        assertThat(gate.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(gate.details()).containsEntry("active", false)
                .containsEntry("connector_name", "billing-api")
                .containsEntry("protocol", "REST");
        assertThat(result.resultingStatus()).isNull();
    }

    // ── 2. Classification ─────────────────────────────────────────────────────

    @Test
    void aMatchingSchemaOperationDecidesTheWriteFlag() {
        var result = service.simulate(organizationId, input("deleteCustomer", "GET"));

        var step = step(result.steps(), ApiDecisionStepKind.CALL_CLASSIFICATION);
        // The verb says GET; the schema says the operation writes, and the schema wins.
        assertThat(step.details()).containsEntry("write", true)
                .containsEntry("classified_from", "SCHEMA_OPERATION");
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
    }

    @Test
    void aRestCallWithNoMatchingOperationFallsBackToTheVerb() {
        var result = service.simulate(organizationId, input(null, "GET"));

        assertThat(step(result.steps(), ApiDecisionStepKind.CALL_CLASSIFICATION).details())
                .containsEntry("write", false)
                .containsEntry("classified_from", "REST_VERB");
    }

    @Test
    void aNonRestProtocolWithNoMatchingOperationFailsSafeToWrite() {
        when(connectorLookupService.findGovernanceView(connectorId, organizationId))
                .thenReturn(Optional.of(new ApiConnectorGovernanceView(connectorId, organizationId,
                        "soap-api", ApiProtocol.SOAP, true, true, null, false, true)));

        var result = service.simulate(organizationId, input(null, "GET"));

        assertThat(step(result.steps(), ApiDecisionStepKind.CALL_CLASSIFICATION).details())
                .containsEntry("write", true)
                .containsEntry("classified_from", "PROTOCOL_DEFAULT");
    }

    // ── 3. Schema validation ──────────────────────────────────────────────────

    @Test
    void anUnknownOperationBlocksTheCall() {
        var result = service.simulate(organizationId, input("nope", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.SCHEMA_VALIDATION);
        assertThat(step.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.schema.unknown_operation");
        assertThat(step.details()).containsEntry("operation_count", 1);
        assertThat(result.resultingStatus()).isNull();
    }

    @Test
    void aConnectorWithNoIngestedSchemaValidatesNothing() {
        when(schemaService.listOperations(connectorId, organizationId)).thenReturn(List.of());

        var result = service.simulate(organizationId, input("anything", "GET"));

        var step = step(result.steps(), ApiDecisionStepKind.SCHEMA_VALIDATION);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.schema.no_catalog");
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aFreeFormCallSkipsTheSchemaStageRatherThanPassingIt() {
        var result = service.simulate(organizationId, input(null, "GET"));

        var step = step(result.steps(), ApiDecisionStepKind.SCHEMA_VALIDATION);
        assertThat(step.outcome()).isEqualTo(StepOutcome.SKIP);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.schema.free_form");
    }

    // ── 4. Permission ─────────────────────────────────────────────────────────

    @Test
    void aQueryAdminHolderSkipsThePerConnectorGateEntirely() {
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of(userId));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.details()).containsEntry("query_admin_short_circuit", true);
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aWriteWithoutWriteAccessIsDenied() {
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, false, List.of(), false)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION).reasonKey())
                .isEqualTo("apigov.simulation.permission.write_missing");
    }

    @Test
    void aReadWithoutReadAccessIsDenied() {
        when(schemaService.listOperations(connectorId, organizationId))
                .thenReturn(List.of(operation("getCustomer", "GET", false)));
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(false, true, List.of(), false)));

        var result = service.simulate(organizationId, input("getCustomer", "GET"));

        assertThat(step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION).reasonKey())
                .isEqualTo("apigov.simulation.permission.read_missing");
    }

    @Test
    void anOperationOutsideTheAllowListIsDenied() {
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, List.of("getCustomer"), false)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.permission.operation_not_allowed");
        assertThat(step.details()).containsEntry("allowed_operations", List.of("getCustomer"));
    }

    @Test
    void anEmptyAllowListMeansEveryOperation() {
        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION).outcome())
                .isEqualTo(StepOutcome.ALLOW);
    }

    // ── 5. The full policy list ───────────────────────────────────────────────

    @Test
    void theRoutingStepCarriesEveryPolicyWithTheDecisiveOneMarked() {
        var winner = UUID.randomUUID();
        var loser = UUID.randomUUID();
        when(decisionEvaluator.evaluate(any())).thenReturn(new ApiDecision(
                ApiDecisionKind.ROUTING_ESCALATE, QueryStatus.PENDING_REVIEW,
                new ApiRoutingPolicyEngine.RoutingMatch(winner, "winner", ApiRoutingAction.ESCALATE,
                        1),
                2, decision(QueryStatus.PENDING_REVIEW).trace()));
        when(routingEngine.evaluateAll(any(), any(), any())).thenReturn(List.of(
                new ApiRoutingPolicyEngine.PolicyEvaluation(loser, "loser", 5,
                        ApiRoutingAction.AUTO_APPROVE, null, false, false),
                new ApiRoutingPolicyEngine.PolicyEvaluation(winner, "winner", 10,
                        ApiRoutingAction.ESCALATE, 1, true, true)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var policies = step(result.steps(), ApiDecisionStepKind.ROUTING_POLICIES).details()
                .get("policies");
        assertThat(policies).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Object.class))
                .hasSize(2);
        assertThat(policies.toString()).contains("decisive=true").contains("loser");
    }

    @Test
    void aFailedAnalysisReportsNoPolicyListBecauseProductionNeverRoutesOne() {
        var trace = decision(QueryStatus.PENDING_REVIEW).trace();
        when(decisionEvaluator.evaluate(any())).thenReturn(new ApiDecision(
                ApiDecisionKind.AI_FAILED_PENDING_REVIEW, QueryStatus.PENDING_REVIEW, null, 1,
                trace));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.ROUTING_POLICIES).details())
                .doesNotContainKey("policies");
        verify(routingEngine, never()).evaluateAll(any(), any(), any());
    }

    // ── 7. Reviewers ──────────────────────────────────────────────────────────

    @Test
    void reviewersAreThePermissionHoldersMinusTheSubmitter() {
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.API_REQUEST_REVIEW)).thenReturn(List.of(reviewerId, userId));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.ELIGIBLE_REVIEWERS);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.details()).containsEntry("submitter_excluded", true);
        verify(userQueryService).findByIds(List.of(reviewerId));
    }

    @Test
    void noReviewerOtherThanTheSubmitterIsADenial() {
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.API_REQUEST_REVIEW)).thenReturn(List.of(userId));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.ELIGIBLE_REVIEWERS);
        assertThat(step.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.reviewers.none");
    }

    @Test
    void aPlanWithApproverRulesNarrowsEligibilityAndDropsTheSubmitter() {
        when(connectorLookupService.findGovernanceView(connectorId, organizationId))
                .thenReturn(Optional.of(connector(true, planId)));
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(
                new ReviewPlanSnapshot(planId, organizationId, true, true, 1, false, 1,
                        List.of(new ApproverRule(reviewerId, null, 1),
                                new ApproverRule(userId, null, 1)),
                        List.of())));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.ELIGIBLE_REVIEWERS);
        assertThat(step.reasonKey()).isEqualTo("apigov.simulation.reviewers.plan_approvers");
        assertThat(step.details().get("plan_approvers").toString())
                .contains(reviewerId.toString())
                .doesNotContain(userId.toString());
    }

    @Test
    void reviewersAreSkippedWhenTheCallWouldNotReachReview() {
        when(decisionEvaluator.evaluate(any())).thenReturn(new ApiDecision(
                ApiDecisionKind.CONNECTOR_APPROVED, QueryStatus.APPROVED, null, null,
                decision(QueryStatus.APPROVED).trace()));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.ELIGIBLE_REVIEWERS).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    // ── 8. Masking ────────────────────────────────────────────────────────────

    @Test
    void maskingIsReportedPerRuleWithTheLegacyEntriesDistinguishable() {
        var maskPolicyId = UUID.randomUUID();
        when(maskingResolutionService.resolveApplicable(organizationId, connectorId, userId))
                .thenReturn(List.of(
                        new ResolvedApiMask(maskPolicyId, ApiMaskingMatcherType.JSON_PATH, null,
                                "customer.iban", MaskingStrategy.PARTIAL, java.util.Map.of()),
                        ResolvedApiMask.legacyRestrictedField("customer.ssn")));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.RESPONSE_MASKING);
        assertThat(step.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(step.details()).containsEntry("restricted_response_field_count", 1);
        assertThat(step.details().get("masks").toString())
                .contains("customer.iban").contains("PARTIAL")
                .contains("policy_id=null");
        assertThat(result.caveats()).contains(SimulationCaveat.RESPONSE_SHAPE_ABSENT);
    }

    @Test
    void noMaskingRuleIsANoMatchRatherThanADenial() {
        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.RESPONSE_MASKING);
        assertThat(step.outcome()).isEqualTo(StepOutcome.NO_MATCH);
        assertThat(step.details()).containsEntry("masks", List.of());
    }

    // ── 9. Break-glass ────────────────────────────────────────────────────────

    @Test
    void breakGlassIsReportedFromTheEffectiveGrant() {
        when(permissionResolver.resolve(connectorId, userId))
                .thenReturn(Optional.of(permission(true, true, List.of(), true)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        var step = step(result.steps(), ApiDecisionStepKind.BREAK_GLASS);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.details()).containsEntry("can_break_glass", true);
    }

    @Test
    void breakGlassIsDeniedWithoutTheGrant() {
        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.BREAK_GLASS).outcome())
                .isEqualTo(StepOutcome.DENY);
    }

    @Test
    void anInactiveReviewerIsNotOffered() {
        when(userQueryService.findByIds(any())).thenReturn(List.of(user(reviewerId, false)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.ELIGIBLE_REVIEWERS).details()
                .get("reviewers")).isEqualTo(List.of());
    }

    @Test
    void anExpiringPermissionReportsItsExpiry() {
        var expiry = Instant.parse("2026-10-01T00:00:00Z");
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.of(
                new ResolvedApiConnectorPermission(connectorId, userId, true, true, false, false,
                        List.of(), List.of(), expiry)));

        var result = service.simulate(organizationId, input("deleteCustomer", "DELETE"));

        assertThat(step(result.steps(), ApiDecisionStepKind.OPERATION_PERMISSION).details())
                .containsEntry("expires_at", expiry);
    }
}
