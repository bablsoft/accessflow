package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetAdminService;
import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetCommand;
import com.bablsoft.accessflow.core.api.DataBudgetNotFoundException;
import com.bablsoft.accessflow.core.api.DataBudgetView;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalDataBudgetException;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDataBudgetAdminService implements DataBudgetAdminService {

    static final int MIN_WINDOW_MINUTES = 60;
    static final int MAX_WINDOW_MINUTES = 44_640;
    static final int DEFAULT_WINDOW_MINUTES = 1_440;
    static final int MAX_NAME_LENGTH = 120;

    private final DataBudgetRepository dataBudgetRepository;
    private final RoleRepository roleRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<DataBudgetView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return dataBudgetRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(organizationId, datasourceId)
                .stream()
                .map(DefaultDataBudgetAdminService::toView)
                .toList();
    }

    @Override
    @Transactional
    public DataBudgetView create(UUID datasourceId, UUID organizationId, DataBudgetCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = new DataBudgetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        apply(entity, organizationId, command);
        return toView(dataBudgetRepository.save(entity));
    }

    @Override
    @Transactional
    public DataBudgetView update(UUID budgetId, UUID datasourceId, UUID organizationId,
                                 DataBudgetCommand command) {
        var entity = loadInScope(budgetId, datasourceId, organizationId);
        apply(entity, organizationId, command);
        return toView(dataBudgetRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID budgetId, UUID datasourceId, UUID organizationId) {
        dataBudgetRepository.delete(loadInScope(budgetId, datasourceId, organizationId));
    }

    private void apply(DataBudgetEntity entity, UUID organizationId, DataBudgetCommand command) {
        var name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalDataBudgetException(msg("error.data_budget.name_invalid"));
        }
        if (command.maxRows() == null && command.maxBytes() == null) {
            throw new IllegalDataBudgetException(msg("error.data_budget.limit_required"));
        }
        if ((command.maxRows() != null && command.maxRows() < 1)
                || (command.maxBytes() != null && command.maxBytes() < 1)) {
            throw new IllegalDataBudgetException(msg("error.data_budget.limit_invalid"));
        }
        var window = command.windowMinutes() == null ? DEFAULT_WINDOW_MINUTES : command.windowMinutes();
        if (window < MIN_WINDOW_MINUTES || window > MAX_WINDOW_MINUTES) {
            throw new IllegalDataBudgetException(msg("error.data_budget.window_invalid"));
        }
        var threshold = command.warnThresholdPercent();
        if (threshold != null && (threshold < 1 || threshold > 99)) {
            throw new IllegalDataBudgetException(msg("error.data_budget.threshold_invalid"));
        }
        var roles = normalizeRoles(organizationId, command.appliesToRoles());
        validateAppliesToTargets(organizationId, command.appliesToUserIds(),
                command.appliesToGroupIds());
        entity.setName(name);
        entity.setMaxRows(command.maxRows());
        entity.setMaxBytes(command.maxBytes());
        entity.setWindowMinutes(window);
        entity.setBreachAction(command.breachAction() == null
                ? DataBudgetBreachAction.REQUIRE_REVIEW : command.breachAction());
        entity.setWarnThresholdPercent(threshold == null ? null : threshold.shortValue());
        entity.setAppliesToRoles(roles.isEmpty() ? null : roles.toArray(new String[0]));
        entity.setAppliesToGroupIds(toUuidArray(command.appliesToGroupIds()));
        entity.setAppliesToUserIds(toUuidArray(command.appliesToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
    }

    private DataBudgetEntity loadInScope(UUID budgetId, UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = dataBudgetRepository.findByIdAndOrganizationId(budgetId, organizationId)
                .orElseThrow(() -> new DataBudgetNotFoundException(budgetId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new DataBudgetNotFoundException(budgetId);
        }
        return entity;
    }

    private void requireDatasourceInOrganization(UUID datasourceId, UUID organizationId) {
        var datasource = datasourceRepository.findById(datasourceId)
                .orElseThrow(() -> new DatasourceNotFoundException(datasourceId));
        if (!datasource.getOrganization().getId().equals(organizationId)) {
            throw new DatasourceNotFoundException(datasourceId);
        }
    }

    private List<String> normalizeRoles(UUID organizationId, List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        var normalized = new ArrayList<String>(roles.size());
        for (var role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            var resolved = roleRepository.findByNameInScope(organizationId, role.trim())
                    .orElseThrow(() -> new IllegalDataBudgetException(
                            msg("error.data_budget.unknown_role", role)));
            normalized.add(resolved.getName());
        }
        return normalized;
    }

    private void validateAppliesToTargets(UUID organizationId, List<UUID> userIds,
                                          List<UUID> groupIds) {
        if (userIds != null && !userIds.isEmpty()) {
            var found = userRepository.findAllByOrganization_IdAndIdIn(organizationId, userIds);
            if (found.size() != userIds.stream().distinct().count()) {
                throw new IllegalDataBudgetException(msg("error.data_budget.user_not_in_org"));
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var found = userGroupRepository.findAllByOrganization_IdAndIdIn(organizationId,
                    new ArrayList<>(groupIds));
            if (found.size() != groupIds.stream().distinct().count()) {
                throw new IllegalDataBudgetException(msg("error.data_budget.group_not_in_org"));
            }
        }
    }

    static DataBudgetView toView(DataBudgetEntity entity) {
        return new DataBudgetView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getName(),
                entity.getMaxRows(),
                entity.getMaxBytes(),
                entity.getWindowMinutes(),
                entity.getBreachAction(),
                entity.getWarnThresholdPercent() == null
                        ? null : entity.getWarnThresholdPercent().intValue(),
                entity.getAppliesToRoles() == null ? List.of() : List.of(entity.getAppliesToRoles()),
                entity.getAppliesToGroupIds() == null ? List.of() : List.of(entity.getAppliesToGroupIds()),
                entity.getAppliesToUserIds() == null ? List.of() : List.of(entity.getAppliesToUserIds()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static UUID[] toUuidArray(List<UUID> values) {
        return values == null || values.isEmpty()
                ? null : values.stream().distinct().toArray(UUID[]::new);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
