package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDataBudgetStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    @Mock DataBudgetRepository dataBudgetRepository;
    @Mock DataBudgetUsageRepository usageRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultDataBudgetStatusService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDataBudgetStatusService(dataBudgetRepository, usageRepository,
                datasourceRepository, userRepository, membershipRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var user = new UserEntity();
        user.setId(userId);
        user.setRole(UserRoleType.ANALYST);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setName("warehouse");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(ds));
        when(datasourceRepository.findAllById(any())).thenReturn(List.of(ds));
        when(usageRepository.sumSince(any(), any(), any())).thenReturn(totals(0, 0));
    }

    @Test
    void noBudgetsReadsAsEmptyWithoutTouchingTheLedger() {
        when(dataBudgetRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId))
                .thenReturn(List.of());

        var status = service.statusFor(datasourceId, userId);

        assertThat(status.isEmpty()).isTrue();
        verify(usageRepository, never()).sumSince(any(), any(), any());
    }

    @Test
    void budgetsScopedToOthersReadAsEmpty() {
        var budget = budget(1_000L, null, 60);
        budget.setAppliesToUserIds(new UUID[]{UUID.randomUUID()});
        when(dataBudgetRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId))
                .thenReturn(List.of(budget));

        assertThat(service.statusFor(datasourceId, userId).isEmpty()).isTrue();
    }

    @Test
    void sumsTheTrailingWindowOncePerDistinctWindow() {
        var daily = budget(1_000L, null, 1440);
        var alsoDaily = budget(null, 10_000L, 1440);
        alsoDaily.setAppliesToGroupIds(new UUID[]{groupId});
        var hourly = budget(100L, null, 60);
        hourly.setAppliesToRoles(new String[]{"analyst"});
        when(dataBudgetRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId))
                .thenReturn(List.of(daily, alsoDaily, hourly));
        when(usageRepository.sumSince(userId, datasourceId, NOW.minusSeconds(86_400)))
                .thenReturn(totals(400, 9_000));
        when(usageRepository.sumSince(userId, datasourceId, NOW.minusSeconds(3_600)))
                .thenReturn(totals(100, 50));

        var status = service.statusFor(datasourceId, userId);

        assertThat(status.datasourceName()).isEqualTo("warehouse");
        assertThat(status.budgets()).hasSize(3);
        assertThat(status.remainingRows()).isZero();
        assertThat(status.remainingBytes()).isEqualTo(1_000L);
        assertThat(status.exhausted()).isTrue();
        verify(usageRepository, times(2)).sumSince(eq(userId), eq(datasourceId), any());
    }

    @Test
    void nullTotalsReadAsZero() {
        when(dataBudgetRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId))
                .thenReturn(List.of(budget(10L, null, 60)));
        when(usageRepository.sumSince(any(), any(), any())).thenReturn(null);

        var status = service.statusFor(datasourceId, userId);

        assertThat(status.budgets().getFirst().usedRows()).isZero();
        assertThat(status.remainingRows()).isEqualTo(10L);
    }

    @Test
    void statusesForUserGroupsByDatasourceAndSkipsForeignScopes() {
        var other = budget(5L, null, 60);
        other.setAppliesToUserIds(new UUID[]{UUID.randomUUID()});
        when(dataBudgetRepository.findAllByOrganizationIdAndEnabledTrue(orgId))
                .thenReturn(List.of(budget(10L, null, 60), budget(20L, null, 60), other));

        var statuses = service.statusesForUser(orgId, userId);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.datasourceId()).isEqualTo(datasourceId);
            assertThat(s.datasourceName()).isEqualTo("warehouse");
            assertThat(s.budgets()).hasSize(2);
        });
    }

    @Test
    void statusesForUserIsEmptyWithoutBudgets() {
        when(dataBudgetRepository.findAllByOrganizationIdAndEnabledTrue(orgId)).thenReturn(List.of());

        assertThat(service.statusesForUser(orgId, userId)).isEmpty();
    }

    private DataBudgetEntity budget(Long maxRows, Long maxBytes, int windowMinutes) {
        var entity = new DataBudgetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setName("b-" + windowMinutes);
        entity.setMaxRows(maxRows);
        entity.setMaxBytes(maxBytes);
        entity.setWindowMinutes(windowMinutes);
        entity.setBreachAction(DataBudgetBreachAction.REQUIRE_REVIEW);
        entity.setWarnThresholdPercent((short) 80);
        entity.setEnabled(true);
        return entity;
    }

    private static DataBudgetUsageRepository.UsageTotals totals(long rows, long bytes) {
        return new DataBudgetUsageRepository.UsageTotals() {
            @Override
            public Long getRowsRead() {
                return rows;
            }

            @Override
            public Long getBytesRead() {
                return bytes;
            }
        };
    }
}
