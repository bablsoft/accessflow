package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiUsageOutcome;
import com.bablsoft.accessflow.apigov.api.IllegalApiUsageRangeException;
import com.bablsoft.accessflow.apigov.events.ApiConnectorBudgetExceededEvent;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUsageDailyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiConnectorUsageServiceTest {

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiConnectorUsageDailyRepository usageRepository;
    @Mock private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    // 23:30 UTC on the last day of November: the month is still November, "today" is the 30th.
    private final Instant now = Instant.parse("2026-11-30T23:30:00Z");
    private final LocalDate today = LocalDate.parse("2026-11-30");
    private final LocalDate monthStart = LocalDate.parse("2026-11-01");

    private final UUID connectorId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private DefaultApiConnectorUsageService service;

    @BeforeEach
    void setUp() {
        // A non-UTC clock zone must not shift the UTC day or month.
        service = new DefaultApiConnectorUsageService(connectorRepository, usageRepository,
                permissionResolver, eventPublisher, Clock.fixed(now, ZoneOffset.ofHours(5)));
    }

    private ApiConnectorEntity connector(Long price, Long budget) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        c.setCostPerCallMicros(price);
        c.setMonthlyBudgetMicros(budget);
        return c;
    }

    private void found(ApiConnectorEntity c) {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(c));
    }

    // ── accrue ────────────────────────────────────────────────────────────────

    @Test
    void executedAccruesOneCallAndThePriceTimesUnitsOnTheUtcDay() {
        found(connector(2_000L, null));

        long spend = service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 3);

        assertThat(spend).isEqualTo(6_000L);
        verify(usageRepository).accumulate(any(), eq(orgId), eq(connectorId), eq(userId), eq(today),
                eq(3L), eq(0L), eq(0L), eq(6_000L), eq(now));
    }

    @Test
    void failedAccruesACallAFailedCallAndThePrice() {
        found(connector(2_000L, null));

        service.accrue(connectorId, orgId, userId, ApiUsageOutcome.FAILED, 1);

        verify(usageRepository).accumulate(any(), eq(orgId), eq(connectorId), eq(userId), eq(today),
                eq(1L), eq(1L), eq(0L), eq(2_000L), eq(now));
    }

    @Test
    void throttledAccruesAThrottledCallAndNoSpend() {
        found(connector(2_000L, 1L));

        long spend = service.accrue(connectorId, orgId, userId, ApiUsageOutcome.THROTTLED, 1);

        assertThat(spend).isZero();
        verify(usageRepository).accumulate(any(), eq(orgId), eq(connectorId), eq(userId), eq(today),
                eq(0L), eq(0L), eq(1L), eq(0L), eq(now));
        verify(usageRepository, never()).sumSpendMicros(any(), any());
    }

    @Test
    void anUnpricedConnectorAccruesTheCallWithZeroSpend() {
        found(connector(null, 10_000L));

        long spend = service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 0);

        assertThat(spend).isZero();
        verify(usageRepository).accumulate(any(), any(), any(), any(), any(), eq(1L), eq(0L), eq(0L),
                eq(0L), any());
        verify(connectorRepository, never()).markBudgetExceededNotified(any(), any());
    }

    @Test
    void aMissingConnectorAccruesNothing() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());

        assertThat(service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 1)).isZero();
        verify(usageRepository, never()).accumulate(any(), any(), any(), any(), any(), anyLong(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reachingTheBudgetStampsTheUtcMonthAndPublishesOnce() {
        found(connector(2_000L, 10_000L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(10_000L);
        when(connectorRepository.markBudgetExceededNotified(connectorId, monthStart)).thenReturn(1);

        service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 1);

        verify(eventPublisher).publishEvent(
                new ApiConnectorBudgetExceededEvent(connectorId, orgId, 10_000L, 10_000L));
    }

    @Test
    void aMonthAlreadyNotifiedPublishesNothing() {
        found(connector(2_000L, 10_000L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(14_000L);
        when(connectorRepository.markBudgetExceededNotified(connectorId, monthStart)).thenReturn(0);

        service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 1);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void underBudgetNeitherStampsNorPublishes() {
        found(connector(2_000L, 10_000L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(9_999L);

        service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 1);

        verify(connectorRepository, never()).markBudgetExceededNotified(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noBudgetSkipsTheMonthToDateSum() {
        found(connector(2_000L, 0L));

        service.accrue(connectorId, orgId, userId, ApiUsageOutcome.EXECUTED, 1);

        verify(usageRepository, never()).sumSpendMicros(any(), any());
    }

    // ── budgetUsedPercent ─────────────────────────────────────────────────────

    @Test
    void percentIsFlooredMonthToDateOverBudget() {
        found(connector(1L, 3_000L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(2_999L);

        assertThat(service.budgetUsedPercent(connectorId)).isEqualTo(99);
    }

    @Test
    void percentMayExceedOneHundred() {
        found(connector(1L, 1_000L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(2_500L);

        assertThat(service.budgetUsedPercent(connectorId)).isEqualTo(250);
    }

    @Test
    void percentIsNullWithoutABudgetOrAPriceOrAConnector() {
        found(connector(1L, null));
        assertThat(service.budgetUsedPercent(connectorId)).isNull();
        found(connector(null, 1_000L));
        assertThat(service.budgetUsedPercent(connectorId)).isNull();
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());
        assertThat(service.budgetUsedPercent(connectorId)).isNull();
    }

    @Test
    void percentSaturatesInsteadOfOverflowing() {
        found(connector(1L, 1L));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(Long.MAX_VALUE);

        assertThat(service.budgetUsedPercent(connectorId)).isEqualTo(Integer.MAX_VALUE);
    }

    // ── usage ────────────────────────────────────────────────────────────────

    private ApiConnectorUsageDailyEntity row(UUID user, LocalDate date, long calls, long spend) {
        var r = new ApiConnectorUsageDailyEntity();
        r.setUserId(user);
        r.setUsageDate(date);
        r.setCalls(calls);
        r.setFailedCalls(1);
        r.setThrottledCalls(2);
        r.setSpendMicros(spend);
        return r;
    }

    @Test
    void adminUsageDefaultsToTheCurrentUtcMonthAndSumsUsersPerDenseDay() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(500L, 10_000L)));
        var other = UUID.randomUUID();
        when(usageRepository.findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(connectorId,
                monthStart, today)).thenReturn(List.of(
                        row(userId, LocalDate.parse("2026-11-02"), 3, 1_500L),
                        row(other, LocalDate.parse("2026-11-02"), 2, 1_000L)));
        when(usageRepository.sumSpendMicros(connectorId, monthStart)).thenReturn(2_500L);

        var view = service.usage(connectorId, orgId, null, null, null);

        assertThat(view.monthStart()).isEqualTo(monthStart);
        assertThat(view.monthToDateSpendMicros()).isEqualTo(2_500L);
        assertThat(view.budgetUsedPercent()).isEqualTo(25);
        assertThat(view.costPerCallMicros()).isEqualTo(500L);
        assertThat(view.monthlyBudgetMicros()).isEqualTo(10_000L);
        assertThat(view.days()).hasSize(30);
        assertThat(view.days().get(0).calls()).isZero();
        assertThat(view.days().get(1)).satisfies(d -> {
            assertThat(d.date()).isEqualTo(LocalDate.parse("2026-11-02"));
            assertThat(d.calls()).isEqualTo(5);
            assertThat(d.failedCalls()).isEqualTo(2);
            assertThat(d.throttledCalls()).isEqualTo(4);
            assertThat(d.spendMicros()).isEqualTo(2_500L);
        });
    }

    @Test
    void aGrantedUsersUsageReadsOnlyTheirOwnRows() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(null, null)));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.of(
                new ResolvedApiConnectorPermission(connectorId, userId, true, false, false, false,
                        List.of(), List.of(), null)));
        var from = LocalDate.parse("2026-10-01");
        var to = LocalDate.parse("2026-10-02");
        when(usageRepository.findByConnectorIdAndUserIdAndUsageDateBetweenOrderByUsageDateAsc(
                connectorId, userId, from, to)).thenReturn(List.of(row(userId, to, 4, 0L)));

        var view = service.usage(connectorId, orgId, userId, from, to);

        assertThat(view.days()).hasSize(2);
        assertThat(view.days().get(1).calls()).isEqualTo(4);
        assertThat(view.budgetUsedPercent()).isNull();
        verify(usageRepository, never()).findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(
                any(), any(), any());
    }

    @Test
    void anUngrantedUserGets404() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(null, null)));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.usage(connectorId, orgId, userId, null, null))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void aGrantWithNeitherReadNorWriteGets404() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(null, null)));
        when(permissionResolver.resolve(connectorId, userId)).thenReturn(Optional.of(
                new ResolvedApiConnectorPermission(connectorId, userId, false, false, false, false,
                        List.of(), List.of(), null)));

        assertThatThrownBy(() -> service.usage(connectorId, orgId, userId, null, null))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void aForeignConnectorGets404() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.usage(connectorId, orgId, null, null, null))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void aNinetyThreeDayRangeIsRejectedAndNinetyTwoIsAccepted() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(null, null)));
        var from = LocalDate.parse("2026-08-01");

        assertThatThrownBy(() -> service.usage(connectorId, orgId, null, from, from.plusDays(92)))
                .isInstanceOf(IllegalApiUsageRangeException.class)
                .satisfies(ex -> assertThat(((IllegalApiUsageRangeException) ex).maxDays()).isEqualTo(92));
        assertThat(service.usage(connectorId, orgId, null, from, from.plusDays(91)).days()).hasSize(92);
    }

    @Test
    void anInvertedRangeIsRejected() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector(null, null)));

        assertThatThrownBy(() -> service.usage(connectorId, orgId, null,
                LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-04")))
                .isInstanceOf(IllegalApiUsageRangeException.class);
    }
}
