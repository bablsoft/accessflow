package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.api.CreateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalRowSecurityPolicyException;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyAdminService;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyView;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.UpdateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowSecurityPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowSecurityPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRowSecurityPolicyAdminService implements RowSecurityPolicyAdminService {

    /** The built-in variable that resolves to a list of values; all others are scalar. */
    private static final String GROUPS_VARIABLE = "user.groups";

    private final RowSecurityPolicyRepository rowSecurityPolicyRepository;
    private final RoleRepository roleRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<RowSecurityPolicyView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(organizationId, datasourceId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public RowSecurityPolicyView create(UUID datasourceId, UUID organizationId,
                                        CreateRowSecurityPolicyCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var operator = requireOperator(command.operator());
        var valueType = requireValueType(command.valueType());
        var valueExpression = normalizeValue(valueType, operator, command.valueExpression());
        var roles = normalizeRoles(organizationId, command.appliesToRoles());
        validateAppliesToTargets(organizationId, command.appliesToUserIds(), command.appliesToGroupIds());

        var entity = new RowSecurityPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName(requireTableName(command.tableName()));
        entity.setColumnName(requireColumnName(command.columnName()));
        entity.setOperator(operator);
        entity.setValueType(valueType);
        entity.setValueExpression(valueExpression);
        entity.setAppliesToRoles(toStringArray(roles));
        entity.setAppliesToGroupIds(toUuidArray(command.appliesToGroupIds()));
        entity.setAppliesToUserIds(toUuidArray(command.appliesToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(rowSecurityPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public RowSecurityPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                                        UpdateRowSecurityPolicyCommand command) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        var operator = requireOperator(command.operator());
        var valueType = requireValueType(command.valueType());
        var valueExpression = normalizeValue(valueType, operator, command.valueExpression());
        var roles = normalizeRoles(organizationId, command.appliesToRoles());
        validateAppliesToTargets(organizationId, command.appliesToUserIds(), command.appliesToGroupIds());

        entity.setTableName(requireTableName(command.tableName()));
        entity.setColumnName(requireColumnName(command.columnName()));
        entity.setOperator(operator);
        entity.setValueType(valueType);
        entity.setValueExpression(valueExpression);
        entity.setAppliesToRoles(toStringArray(roles));
        entity.setAppliesToGroupIds(toUuidArray(command.appliesToGroupIds()));
        entity.setAppliesToUserIds(toUuidArray(command.appliesToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(rowSecurityPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID datasourceId, UUID organizationId) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        rowSecurityPolicyRepository.delete(entity);
    }

    private RowSecurityPolicyEntity loadInScope(UUID policyId, UUID datasourceId,
                                                UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = rowSecurityPolicyRepository.findByIdAndOrganizationId(policyId, organizationId)
                .orElseThrow(() -> new RowSecurityPolicyNotFoundException(policyId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new RowSecurityPolicyNotFoundException(policyId);
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

    private String requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalRowSecurityPolicyException(msg("error.row_security_table_required"));
        }
        return tableName.trim();
    }

    private String requireColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalRowSecurityPolicyException(msg("error.row_security_column_required"));
        }
        return columnName.trim();
    }

    private RowSecurityOperator requireOperator(RowSecurityOperator operator) {
        if (operator == null) {
            throw new IllegalRowSecurityPolicyException(msg("error.row_security_operator_required"));
        }
        return operator;
    }

    private RowSecurityValueType requireValueType(RowSecurityValueType valueType) {
        if (valueType == null) {
            throw new IllegalRowSecurityPolicyException(msg("error.row_security_value_type_required"));
        }
        return valueType;
    }

    /**
     * Validates and canonicalises the value expression. LITERALs are kept as-is (non-blank). For
     * VARIABLEs the leading {@code :} is stripped, the {@code user.<key>} namespace is enforced, and
     * the list/scalar arity is checked against the operator: the list-valued {@code user.groups}
     * built-in requires {@code IN}/{@code NOT_IN}.
     */
    private String normalizeValue(RowSecurityValueType valueType, RowSecurityOperator operator,
                                  String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalRowSecurityPolicyException(msg("error.row_security_value_required"));
        }
        var value = rawValue.trim();
        if (valueType == RowSecurityValueType.LITERAL) {
            return value;
        }
        var variable = value.startsWith(":") ? value.substring(1).trim() : value;
        if (!variable.startsWith("user.") || variable.length() <= "user.".length()) {
            throw new IllegalRowSecurityPolicyException(
                    msg("error.row_security_unknown_variable", variable));
        }
        if (variable.equals(GROUPS_VARIABLE) && !operator.isMultiValue()) {
            throw new IllegalRowSecurityPolicyException(
                    msg("error.row_security_list_variable_requires_in"));
        }
        return variable;
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
                    .orElseThrow(() -> new IllegalRowSecurityPolicyException(
                            msg("error.row_security_unknown_role", role)));
            normalized.add(resolved.getName());
        }
        return normalized;
    }

    private void validateAppliesToTargets(UUID organizationId, List<UUID> userIds,
                                          List<UUID> groupIds) {
        if (userIds != null && !userIds.isEmpty()) {
            var found = userRepository.findAllByOrganization_IdAndIdIn(organizationId, userIds);
            if (found.size() != distinctCount(userIds)) {
                throw new IllegalRowSecurityPolicyException(
                        msg("error.row_security_applies_user_not_in_org"));
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var found = userGroupRepository.findAllByOrganization_IdAndIdIn(organizationId,
                    new ArrayList<>(groupIds));
            if (found.size() != distinctCount(groupIds)) {
                throw new IllegalRowSecurityPolicyException(
                        msg("error.row_security_applies_group_not_in_org"));
            }
        }
    }

    private static long distinctCount(List<UUID> ids) {
        return ids.stream().distinct().count();
    }

    private RowSecurityPolicyView toView(RowSecurityPolicyEntity entity) {
        return new RowSecurityPolicyView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getTableName(),
                entity.getColumnName(),
                entity.getOperator(),
                entity.getValueType(),
                entity.getValueExpression(),
                toStringList(entity.getAppliesToRoles()),
                toUuidList(entity.getAppliesToGroupIds()),
                toUuidList(entity.getAppliesToUserIds()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private static String[] toStringArray(List<String> values) {
        return values == null || values.isEmpty() ? null : values.toArray(new String[0]);
    }

    private static UUID[] toUuidArray(List<UUID> values) {
        return values == null || values.isEmpty() ? null : values.toArray(new UUID[0]);
    }

    private static List<String> toStringList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private static List<UUID> toUuidList(UUID[] values) {
        return values == null ? List.of() : List.of(values);
    }
}
