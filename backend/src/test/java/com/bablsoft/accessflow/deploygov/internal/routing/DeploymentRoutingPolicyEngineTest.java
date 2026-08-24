package com.bablsoft.accessflow.deploygov.internal.routing;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentRoutingPolicyEngineTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PIPELINE = UUID.randomUUID();
    /** Friday 2026-08-21, 17:30 UTC. */
    private static final Instant FRIDAY_1730_UTC = Instant.parse("2026-08-21T17:30:00Z");

    private DeploymentRoutingPolicyRepository repository;
    private DeploymentRoutingPolicyEngine engine;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentRoutingPolicyRepository.class);
        engine = new DeploymentRoutingPolicyEngine(repository,
                new DeploymentRoutingConditionCodec(JsonMapper.builder().build()));
    }

    @Test
    void noPoliciesMeansNoMatch() {
        stub();

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void lowestPriorityWinsBecauseTheRepositoryOrders() {
        var first = policy("{}", DeploymentRoutingAction.AUTO_APPROVE, null, 10, null);
        var second = policy("{}", DeploymentRoutingAction.AUTO_REJECT, null, 20, null);
        stub(first, second);

        var match = engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW));

        assertThat(match).isNotNull();
        assertThat(match.policyId()).isEqualTo(first.getId());
        assertThat(match.action()).isEqualTo(DeploymentRoutingAction.AUTO_APPROVE);
    }

    @Test
    void pipelineScopedPolicyIsSkippedForAnotherPipeline() {
        stub(policy("{}", DeploymentRoutingAction.AUTO_REJECT, null, 10, UUID.randomUUID()));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void pipelineScopedPolicyMatchesItsOwnPipeline() {
        stub(policy("{}", DeploymentRoutingAction.ESCALATE, 2, 10, PIPELINE));

        var match = engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW));

        assertThat(match).isNotNull();
        assertThat(match.requiredApprovals()).isEqualTo(2);
    }

    @Test
    void environmentLeafIsCaseInsensitiveAnyOf() {
        stub(policy("{\"environments\":[\"PRODUCTION\",\"prod-eu\"]}",
                DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
        assertThat(engine.evaluate(ORG, PIPELINE, new DeploymentRoutingPolicyEngine.RoutingContext(
                "staging", PipelineProvider.GITHUB_ACTIONS, "2.4.1", RiskLevel.LOW,
                FRIDAY_1730_UTC))).isNull();
    }

    @Test
    void providerLeafComparesNamesWithoutThrowingOnAnAdminTypo() {
        stub(policy("{\"providers\":[\"not_a_provider\"]}", DeploymentRoutingAction.AUTO_REJECT,
                null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void providerLeafMatchesTheRequestProvider() {
        stub(policy("{\"providers\":[\"github_actions\"]}", DeploymentRoutingAction.AUTO_APPROVE,
                null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
    }

    @Test
    void riskGateNeedsAtLeastTheConfiguredLevel() {
        stub(policy("{\"minRiskLevel\":\"HIGH\"}", DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.CRITICAL))).isNotNull();
        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.MEDIUM))).isNull();
    }

    @Test
    void riskGateNeverMatchesWhenNoAnalysisProducedARisk() {
        stub(policy("{\"minRiskLevel\":\"LOW\"}", DeploymentRoutingAction.AUTO_APPROVE, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(null))).isNull();
    }

    @Test
    void versionGlobLeafMatchesAnyOf() {
        stub(policy("{\"versionGlobs\":[\"1.*\",\"2.*\"]}", DeploymentRoutingAction.AUTO_APPROVE,
                null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
        assertThat(engine.evaluate(ORG, PIPELINE, new DeploymentRoutingPolicyEngine.RoutingContext(
                "production", PipelineProvider.GITHUB_ACTIONS, "3.0.0", RiskLevel.LOW,
                FRIDAY_1730_UTC))).isNull();
    }

    @Test
    void leavesAreAndedTogether() {
        stub(policy("{\"environments\":[\"production\"],\"versionGlobs\":[\"9.*\"]}",
                DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void dayAndTimeWindowMatchesInUtcByDefault() {
        stub(policy("{\"daysOfWeek\":[5],\"startTime\":\"16:00\",\"endTime\":\"23:00\"}",
                DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
    }

    @Test
    void dayAndTimeWindowIsEvaluatedInTheConfiguredZone() {
        // 17:30Z on a Friday is 03:30 on Saturday in Auckland, so the Friday window no longer holds.
        stub(policy("{\"daysOfWeek\":[5],\"startTime\":\"16:00\",\"endTime\":\"23:00\","
                + "\"timezone\":\"Pacific/Auckland\"}", DeploymentRoutingAction.AUTO_REJECT, null,
                10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void midnightSpanningWindowBelongsToTheDayItStarts() {
        // Fri 22:00 -> Sat 04:00 listed on Friday. 17:30Z Friday is outside; 02:00Z Saturday is in.
        var conditions = "{\"daysOfWeek\":[5],\"startTime\":\"22:00\",\"endTime\":\"04:00\"}";
        stub(policy(conditions, DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
        assertThat(engine.evaluate(ORG, PIPELINE, new DeploymentRoutingPolicyEngine.RoutingContext(
                "production", PipelineProvider.GITHUB_ACTIONS, "2.4.1", RiskLevel.LOW,
                Instant.parse("2026-08-22T02:00:00Z")))).isNotNull();
    }

    @Test
    void daysWithoutTimesCoverTheWholeLocalDay() {
        stub(policy("{\"daysOfWeek\":[5]}", DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
        assertThat(engine.evaluate(ORG, PIPELINE, new DeploymentRoutingPolicyEngine.RoutingContext(
                "production", PipelineProvider.GITHUB_ACTIONS, "2.4.1", RiskLevel.LOW,
                Instant.parse("2026-08-22T02:00:00Z")))).isNull();
    }

    @Test
    void timesWithoutDaysApplyEveryDay() {
        stub(policy("{\"startTime\":\"16:00\",\"endTime\":\"23:00\"}",
                DeploymentRoutingAction.AUTO_REJECT, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNotNull();
        assertThat(engine.evaluate(ORG, PIPELINE, new DeploymentRoutingPolicyEngine.RoutingContext(
                "production", PipelineProvider.GITHUB_ACTIONS, "2.4.1", RiskLevel.LOW,
                Instant.parse("2026-08-22T02:00:00Z")))).isNull();
    }

    @Test
    void unreadableConditionsSkipThePolicyRatherThanMatchingIt() {
        var broken = policy("{not json", DeploymentRoutingAction.AUTO_APPROVE, null, 10, null);
        var good = policy("{}", DeploymentRoutingAction.AUTO_REJECT, null, 20, null);
        stub(broken, good);

        var match = engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW));

        assertThat(match).isNotNull();
        assertThat(match.policyId()).isEqualTo(good.getId());
    }

    @Test
    void unknownTimezoneSkipsThePolicy() {
        stub(policy("{\"daysOfWeek\":[5],\"startTime\":\"16:00\",\"endTime\":\"23:00\","
                + "\"timezone\":\"Mars/Olympus\"}", DeploymentRoutingAction.AUTO_APPROVE, null,
                10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    @Test
    void equalStartAndEndTimesSkipThePolicy() {
        stub(policy("{\"startTime\":\"16:00\",\"endTime\":\"16:00\"}",
                DeploymentRoutingAction.AUTO_APPROVE, null, 10, null));

        assertThat(engine.evaluate(ORG, PIPELINE, context(RiskLevel.LOW))).isNull();
    }

    private void stub(DeploymentRoutingPolicyEntity... policies) {
        when(repository.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(ORG))
                .thenReturn(List.of(policies));
    }

    private static DeploymentRoutingPolicyEngine.RoutingContext context(RiskLevel riskLevel) {
        return new DeploymentRoutingPolicyEngine.RoutingContext("production",
                PipelineProvider.GITHUB_ACTIONS, "2.4.1", riskLevel, FRIDAY_1730_UTC);
    }

    private static DeploymentRoutingPolicyEntity policy(String conditions,
                                                        DeploymentRoutingAction action,
                                                        Integer requiredApprovals, int priority,
                                                        UUID pipelineId) {
        var entity = new DeploymentRoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setPipelineId(pipelineId);
        entity.setName("policy-" + priority);
        entity.setConditions(conditions);
        entity.setAction(action);
        entity.setRequiredApprovals(requiredApprovals);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
