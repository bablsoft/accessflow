package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDataBudgetStatusService implements DataBudgetStatusService {

    private final DataBudgetRepository dataBudgetRepository;
    private final DataBudgetUsageRepository usageRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupMembershipRepository membershipRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DataBudgetStatus statusFor(UUID datasourceId, UUID userId) {
        var budgets = dataBudgetRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId);
        if (budgets.isEmpty()) {
            return DataBudgetStatus.none(datasourceId);
        }
        var scope = scopeOf(userId);
        var applying = budgets.stream().filter(b -> scope.matches(b, userId)).toList();
        if (applying.isEmpty()) {
            return DataBudgetStatus.none(datasourceId);
        }
        var name = datasourceRepository.findById(datasourceId).map(DatasourceEntity::getName)
                .orElse(null);
        return evaluate(datasourceId, name, userId, applying);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataBudgetStatus> statusesForUser(UUID organizationId, UUID userId) {
        var budgets = dataBudgetRepository.findAllByOrganizationIdAndEnabledTrue(organizationId);
        if (budgets.isEmpty()) {
            return List.of();
        }
        var scope = scopeOf(userId);
        var byDatasource = new LinkedHashMap<UUID, List<DataBudgetEntity>>();
        budgets.stream()
                .filter(b -> scope.matches(b, userId))
                .sorted(Comparator.comparing(DataBudgetEntity::getCreatedAt))
                .forEach(b -> byDatasource.computeIfAbsent(b.getDatasourceId(), k -> new ArrayList<>())
                        .add(b));
        var names = new HashMap<UUID, String>();
        datasourceRepository.findAllById(byDatasource.keySet())
                .forEach(ds -> names.put(ds.getId(), ds.getName()));
        var statuses = new ArrayList<DataBudgetStatus>(byDatasource.size());
        byDatasource.forEach((dsId, applying) ->
                statuses.add(evaluate(dsId, names.get(dsId), userId, applying)));
        statuses.sort(Comparator.comparing(s -> s.datasourceName() == null ? "" : s.datasourceName(),
                String.CASE_INSENSITIVE_ORDER));
        return statuses;
    }

    private DataBudgetStatus evaluate(UUID datasourceId, String datasourceName, UUID userId,
                                      List<DataBudgetEntity> applying) {
        var now = clock.instant();
        // Budgets sharing a window share one ledger sum.
        Map<Integer, DataBudgetUsageRepository.UsageTotals> totalsByWindow = new HashMap<>();
        var consumptions = new ArrayList<DataBudgetConsumption>(applying.size());
        for (var budget : applying) {
            var totals = totalsByWindow.computeIfAbsent(budget.getWindowMinutes(),
                    window -> usageRepository.sumSince(userId, datasourceId,
                            now.minus(Duration.ofMinutes(window))));
            consumptions.add(new DataBudgetConsumption(
                    budget.getId(),
                    budget.getName(),
                    budget.getMaxRows(),
                    budget.getMaxBytes(),
                    budget.getWindowMinutes(),
                    budget.getBreachAction(),
                    budget.getWarnThresholdPercent() == null
                            ? null : budget.getWarnThresholdPercent().intValue(),
                    valueOf(totals == null ? null : totals.getRowsRead()),
                    valueOf(totals == null ? null : totals.getBytesRead())));
        }
        return new DataBudgetStatus(datasourceId, datasourceName, consumptions);
    }

    private Scope scopeOf(UUID userId) {
        var roleName = userRepository.findById(userId).map(u -> u.roleName()).orElse(null);
        return new Scope(roleName, new HashSet<>(membershipRepository.findGroupIdsForUser(userId)));
    }

    private static long valueOf(Long value) {
        return value == null ? 0L : value;
    }

    private record Scope(String roleName, Set<UUID> groupIds) {

        boolean matches(DataBudgetEntity budget, UUID userId) {
            return AppliesToMatcher.matches(budget.getAppliesToRoles(), budget.getAppliesToGroupIds(),
                    budget.getAppliesToUserIds(), userId, roleName, groupIds);
        }
    }
}
