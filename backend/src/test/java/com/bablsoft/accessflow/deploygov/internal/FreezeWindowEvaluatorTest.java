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

@ExtendWith(MockitoExtension.class)
class FreezeWindowEvaluatorTest {

    @Mock
    private DeploymentFreezeWindowRepository freezeWindowRepository;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    // A Friday: 2026-08-21 is a Friday (ISO day 5).
    private static final Instant FRIDAY_NOON_UTC = Instant.parse("2026-08-21T12:00:00Z");

    private FreezeWindowEvaluator evaluator(Instant now) {
        return new FreezeWindowEvaluator(freezeWindowRepository,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void stubWindows(DeploymentFreezeWindowEntity... windows) {
        when(freezeWindowRepository.findByOrganizationIdAndEnabledTrue(orgId))
                .thenReturn(List.of(windows));
    }

    @Test
    void noWindowsMeansNoFreeze() {
        stubWindows();
        assertThat(evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void oneOffWindowIsActiveInsideItsBounds() {
        var window = oneOff(FRIDAY_NOON_UTC.minusSeconds(600), FRIDAY_NOON_UTC.plusSeconds(600));
        stubWindows(window);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        assertThat(freeze.windowId()).isEqualTo(window.getId());
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
        assertThat(freeze.reason()).isEqualTo("maintenance");
    }

    @Test
    void oneOffWindowBoundsAreStartInclusiveEndExclusive() {
        var start = FRIDAY_NOON_UTC;
        var end = FRIDAY_NOON_UTC.plusSeconds(600);
        stubWindows(oneOff(start, end));

        assertThat(evaluator(start).evaluate(orgId, pipelineId, environmentId)).isPresent();
        assertThat(evaluator(end).evaluate(orgId, pipelineId, environmentId)).isEmpty();
        assertThat(evaluator(start.minusSeconds(1)).evaluate(orgId, pipelineId, environmentId))
                .isEmpty();
    }

    @Test
    void recurringWindowIsActiveOnAListedDayInsideItsLocalTimes() {
        // 12:00 UTC on a Friday is 14:00 in Europe/Berlin (CEST, UTC+2 in August).
        var window = recurring(new short[]{5}, LocalTime.of(13, 0), LocalTime.of(15, 0),
                "Europe/Berlin");
        stubWindows(window);

        assertThat(evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId))
                .isPresent();
    }

    @Test
    void recurringWindowIsInactiveOutsideItsLocalTimes() {
        // 14:00 Berlin local is outside a 15:00-18:00 window.
        stubWindows(recurring(new short[]{5}, LocalTime.of(15, 0), LocalTime.of(18, 0),
                "Europe/Berlin"));

        assertThat(evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void recurringWindowIsInactiveOnAnUnlistedDay() {
        // Friday, but the window only covers Saturday and Sunday.
        stubWindows(recurring(new short[]{6, 7}, LocalTime.of(0, 0), LocalTime.of(23, 59),
                "Europe/Berlin"));

        assertThat(evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void recurringWindowHonoursTheConfiguredTimezone() {
        // 17:00 UTC Friday is already Saturday 02:00 in Asia/Tokyo (UTC+9) — a Saturday-only
        // early-morning window in Tokyo must match while the same instant is still Friday in UTC.
        var lateFridayUtc = Instant.parse("2026-08-21T17:00:00Z");
        stubWindows(recurring(new short[]{6}, LocalTime.of(1, 0), LocalTime.of(3, 0), "Asia/Tokyo"));

        assertThat(evaluator(lateFridayUtc).evaluate(orgId, pipelineId, environmentId)).isPresent();
    }

    @Test
    void midnightSpanningWindowCoversTheEveningSide() {
        // Friday 23:00 Berlin (21:00 UTC) inside a Friday 22:00-02:00 window.
        var eveningUtc = Instant.parse("2026-08-21T21:00:00Z");
        stubWindows(recurring(new short[]{5}, LocalTime.of(22, 0), LocalTime.of(2, 0),
                "Europe/Berlin"));

        assertThat(evaluator(eveningUtc).evaluate(orgId, pipelineId, environmentId)).isPresent();
    }

    @Test
    void midnightSpanningWindowCoversTheEarlyMorningTailOfTheNextDay() {
        // Saturday 01:00 Berlin (Friday 23:00 UTC) belongs to Friday's 22:00-02:00 window.
        var earlySaturdayUtc = Instant.parse("2026-08-21T23:00:00Z");
        stubWindows(recurring(new short[]{5}, LocalTime.of(22, 0), LocalTime.of(2, 0),
                "Europe/Berlin"));

        assertThat(evaluator(earlySaturdayUtc).evaluate(orgId, pipelineId, environmentId))
                .isPresent();
    }

    @Test
    void midnightSpanningWindowDoesNotCoverTheMorningOfItsOwnStartDay() {
        // Friday 01:00 Berlin (Thursday 23:00 UTC) is Thursday's tail, not Friday's.
        var earlyFridayUtc = Instant.parse("2026-08-20T23:00:00Z");
        stubWindows(recurring(new short[]{5}, LocalTime.of(22, 0), LocalTime.of(2, 0),
                "Europe/Berlin"));

        assertThat(evaluator(earlyFridayUtc).evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void scopeMatchingIgnoresWindowsForOtherPipelinesAndEnvironments() {
        var otherPipeline = oneOff(FRIDAY_NOON_UTC.minusSeconds(60), FRIDAY_NOON_UTC.plusSeconds(60));
        otherPipeline.setPipelineId(UUID.randomUUID());
        var otherEnvironment = oneOff(FRIDAY_NOON_UTC.minusSeconds(60),
                FRIDAY_NOON_UTC.plusSeconds(60));
        otherEnvironment.setPipelineId(pipelineId);
        otherEnvironment.setEnvironmentId(UUID.randomUUID());
        stubWindows(otherPipeline, otherEnvironment);

        assertThat(evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)).isEmpty();
    }

    @Test
    void mostSpecificScopeWins() {
        var orgWide = oneOff(FRIDAY_NOON_UTC.minusSeconds(60), FRIDAY_NOON_UTC.plusSeconds(60));
        orgWide.setBehavior(FreezeBehavior.REJECT);
        var pipelineScoped = oneOff(FRIDAY_NOON_UTC.minusSeconds(60),
                FRIDAY_NOON_UTC.plusSeconds(60));
        pipelineScoped.setPipelineId(pipelineId);
        var environmentScoped = oneOff(FRIDAY_NOON_UTC.minusSeconds(60),
                FRIDAY_NOON_UTC.plusSeconds(60));
        environmentScoped.setPipelineId(pipelineId);
        environmentScoped.setEnvironmentId(environmentId);
        stubWindows(orgWide, pipelineScoped, environmentScoped);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        assertThat(freeze.windowId()).isEqualTo(environmentScoped.getId());
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void rejectWinsOverHoldWithinTheSameScopeTier() {
        var hold = oneOff(FRIDAY_NOON_UTC.minusSeconds(60), FRIDAY_NOON_UTC.plusSeconds(60));
        var reject = oneOff(FRIDAY_NOON_UTC.minusSeconds(60), FRIDAY_NOON_UTC.plusSeconds(60));
        reject.setBehavior(FreezeBehavior.REJECT);
        stubWindows(hold, reject);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.REJECT);
        assertThat(freeze.windowId()).isEqualTo(reject.getId());
    }

    @Test
    void invalidTimezoneFailsClosedAsHold() {
        var broken = recurring(new short[]{5}, LocalTime.of(0, 0), LocalTime.of(1, 0),
                "Not/AZone");
        broken.setBehavior(FreezeBehavior.REJECT);
        stubWindows(broken);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        // Fail closed — but never REJECT off a broken definition.
        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
        assertThat(freeze.windowId()).isEqualTo(broken.getId());
    }

    @Test
    void outOfRangeDayNumberFailsClosedAsHold() {
        var broken = recurring(new short[]{9}, LocalTime.of(0, 0), LocalTime.of(1, 0),
                "Europe/Berlin");
        stubWindows(broken);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void inconsistentShapeFailsClosedAsHold() {
        var broken = new DeploymentFreezeWindowEntity();
        broken.setId(UUID.randomUUID());
        broken.setOrganizationId(orgId);
        broken.setBehavior(FreezeBehavior.REJECT);
        broken.setStartsAt(FRIDAY_NOON_UTC.minusSeconds(60));
        // endsAt missing — the DB CHECK forbids this, but the evaluator must not trust it.
        stubWindows(broken);

        var freeze = evaluator(FRIDAY_NOON_UTC).evaluate(orgId, pipelineId, environmentId)
                .orElseThrow();

        assertThat(freeze.behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void explicitInstantOverloadEvaluatesAtThatInstant() {
        var window = oneOff(FRIDAY_NOON_UTC.plusSeconds(3600), FRIDAY_NOON_UTC.plusSeconds(7200));
        stubWindows(window);
        var evaluator = evaluator(FRIDAY_NOON_UTC);

        assertThat(evaluator.evaluate(orgId, pipelineId, environmentId)).isEmpty();
        assertThat(evaluator.evaluate(orgId, pipelineId, environmentId,
                FRIDAY_NOON_UTC.plusSeconds(5400))).isPresent();
    }

    private DeploymentFreezeWindowEntity oneOff(Instant startsAt, Instant endsAt) {
        var e = new DeploymentFreezeWindowEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setStartsAt(startsAt);
        e.setEndsAt(endsAt);
        e.setBehavior(FreezeBehavior.HOLD);
        e.setReason("maintenance");
        return e;
    }

    private DeploymentFreezeWindowEntity recurring(short[] days, LocalTime start, LocalTime end,
                                                   String timezone) {
        var e = new DeploymentFreezeWindowEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setDaysOfWeek(days);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setTimezone(timezone);
        e.setBehavior(FreezeBehavior.HOLD);
        return e;
    }
}
