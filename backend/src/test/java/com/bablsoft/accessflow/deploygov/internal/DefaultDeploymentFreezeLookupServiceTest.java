package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Wraps a <em>real</em> {@link FreezeWindowEvaluator} (mocked repository, fixed clock) so the api
 * view is asserted through the delegation — the point of #877's contract is that the evaluator's
 * fail-closed and precedence semantics survive the hop into another module unchanged.
 */
@ExtendWith(MockitoExtension.class)
class DefaultDeploymentFreezeLookupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    @Mock
    private DeploymentFreezeWindowRepository freezeWindowRepository;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private DefaultDeploymentFreezeLookupService service() {
        return new DefaultDeploymentFreezeLookupService(
                new FreezeWindowEvaluator(freezeWindowRepository, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private void stubWindows(DeploymentFreezeWindowEntity... windows) {
        when(freezeWindowRepository.findByOrganizationIdAndEnabledTrue(orgId))
                .thenReturn(List.of(windows));
    }

    @Test
    void emptyWhenNoWindowIsActive() {
        stubWindows(oneOff(NOW.plusSeconds(60), NOW.plusSeconds(120)));

        assertThat(service().evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void mapsTheWinningWindowOntoTheApiView() {
        var window = oneOff(NOW.minusSeconds(60), NOW.plusSeconds(60));
        window.setReason("release freeze");
        stubWindows(window);

        var freeze = service().evaluate(orgId, pipelineId, environmentId).orElseThrow();

        assertThat(freeze.windowId()).isEqualTo(window.getId());
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
        assertThat(freeze.reason()).isEqualTo("release freeze");
    }

    @Test
    void mostSpecificScopeWinsThroughTheDelegation() {
        var orgWide = oneOff(NOW.minusSeconds(60), NOW.plusSeconds(60));
        orgWide.setBehavior(FreezeBehavior.REJECT);
        var environmentScoped = oneOff(NOW.minusSeconds(60), NOW.plusSeconds(60));
        environmentScoped.setPipelineId(pipelineId);
        environmentScoped.setEnvironmentId(environmentId);
        stubWindows(orgWide, environmentScoped);

        var freeze = service().evaluate(orgId, pipelineId, environmentId).orElseThrow();

        assertThat(freeze.windowId()).isEqualTo(environmentScoped.getId());
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void rejectBeatsHoldWithinTheSameScopeTier() {
        var hold = oneOff(NOW.minusSeconds(60), NOW.plusSeconds(60));
        var reject = oneOff(NOW.minusSeconds(60), NOW.plusSeconds(60));
        reject.setBehavior(FreezeBehavior.REJECT);
        stubWindows(hold, reject);

        var freeze = service().evaluate(orgId, pipelineId, environmentId).orElseThrow();

        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.REJECT);
        assertThat(freeze.windowId()).isEqualTo(reject.getId());
    }

    @Test
    void unevaluableWindowFailsClosedAsHoldNeverReject() {
        var broken = new DeploymentFreezeWindowEntity();
        broken.setId(UUID.randomUUID());
        broken.setOrganizationId(orgId);
        broken.setBehavior(FreezeBehavior.REJECT);
        broken.setDaysOfWeek(new short[]{5});
        broken.setStartTime(LocalTime.of(0, 0));
        broken.setEndTime(LocalTime.of(1, 0));
        broken.setTimezone("Not/AZone");
        stubWindows(broken);

        var freeze = service().evaluate(orgId, pipelineId, environmentId).orElseThrow();

        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
        assertThat(freeze.windowId()).isEqualTo(broken.getId());
    }

    @Test
    void explicitInstantOverloadEvaluatesAtThatInstant() {
        stubWindows(oneOff(NOW.plusSeconds(3600), NOW.plusSeconds(7200)));
        var service = service();

        assertThat(service.evaluate(orgId, pipelineId, environmentId)).isEmpty();
        assertThat(service.evaluate(orgId, pipelineId, environmentId, NOW.plusSeconds(5400)))
                .isPresent();
    }

    private DeploymentFreezeWindowEntity oneOff(Instant startsAt, Instant endsAt) {
        var e = new DeploymentFreezeWindowEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setStartsAt(startsAt);
        e.setEndsAt(endsAt);
        e.setBehavior(FreezeBehavior.HOLD);
        return e;
    }
}
