package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateRowLimitPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalRowLimitPolicyException;
import com.bablsoft.accessflow.core.api.RowLimitPolicyAdminService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.RowLimitPolicyView;
import com.bablsoft.accessflow.core.api.UpdateRowLimitPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowLimitPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowLimitPolicyRepository;
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
class DefaultRowLimitPolicyAdminService implements RowLimitPolicyAdminService {

    private final RowLimitPolicyRepository rowLimitPolicyRepository;
    private final RoleRepository roleRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<RowLimitPolicyView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(organizationId, datasourceId)
                .stream()
                .map(DefaultRowLimitPolicyAdminService::toView)
                .toList();
    }

    @Override
    @Transactional
    public RowLimitPolicyView create(UUID datasourceId, UUID organizationId,
                                     CreateRowLimitPolicyCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = new RowLimitPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        apply(entity, organizationId, command.schemaName(), command.tableName(), command.maxRows(),
                command.appliesToRoles(), command.appliesToGroupIds(), command.appliesToUserIds(),
                command.enabled());
        return toView(rowLimitPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public RowLimitPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                                     UpdateRowLimitPolicyCommand command) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        apply(entity, organizationId, command.schemaName(), command.tableName(), command.maxRows(),
                command.appliesToRoles(), command.appliesToGroupIds(), command.appliesToUserIds(),
                command.enabled());
        return toView(rowLimitPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID datasourceId, UUID organizationId) {
        rowLimitPolicyRepository.delete(loadInScope(policyId, datasourceId, organizationId));
    }

    @SuppressWarnings("java:S107")
    private void apply(RowLimitPolicyEntity entity, UUID organizationId, String schemaName,
                       String tableName, Integer maxRows, List<String> roles, List<UUID> groupIds,
                       List<UUID> userIds, Boolean enabled) {
        var table = normalizeIdentifier(tableName);
        if (table.isEmpty()) {
            throw new IllegalRowLimitPolicyException(msg("error.row_limit_table_required"));
        }
        if (maxRows == null || maxRows < 1) {
            throw new IllegalRowLimitPolicyException(msg("error.row_limit_max_rows_invalid"));
        }
        var normalizedRoles = normalizeRoles(organizationId, roles);
        validateAppliesToTargets(organizationId, userIds, groupIds);
        var schema = normalizeIdentifier(schemaName);
        entity.setSchemaName(schema.isEmpty() ? null : schema);
        entity.setTableName(table);
        entity.setMaxRows(maxRows);
        entity.setAppliesToRoles(toStringArray(normalizedRoles));
        entity.setAppliesToGroupIds(toUuidArray(groupIds));
        entity.setAppliesToUserIds(toUuidArray(userIds));
        entity.setEnabled(enabled == null || enabled);
    }

    private RowLimitPolicyEntity loadInScope(UUID policyId, UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = rowLimitPolicyRepository.findByIdAndOrganizationId(policyId, organizationId)
                .orElseThrow(() -> new RowLimitPolicyNotFoundException(policyId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new RowLimitPolicyNotFoundException(policyId);
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

    /**
     * Quotes stripped, trimmed, ASCII-lowercased — the same normalization the SQL parser applies to
     * {@code referencedTables}, so the resolver compares stored names to parsed ones directly.
     */
    static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        var stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '"' && c != '`' && c != '[' && c != ']') {
                stripped.append(c);
            }
        }
        return stripped.toString().trim().toLowerCase(Locale.ROOT);
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
                    .orElseThrow(() -> new IllegalRowLimitPolicyException(
                            msg("error.row_limit_unknown_role", role)));
            normalized.add(resolved.getName());
        }
        return normalized;
    }

    private void validateAppliesToTargets(UUID organizationId, List<UUID> userIds,
                                          List<UUID> groupIds) {
        if (userIds != null && !userIds.isEmpty()) {
            var found = userRepository.findAllByOrganization_IdAndIdIn(organizationId, userIds);
            if (found.size() != distinctCount(userIds)) {
                throw new IllegalRowLimitPolicyException(
                        msg("error.row_limit_applies_user_not_in_org"));
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var found = userGroupRepository.findAllByOrganization_IdAndIdIn(organizationId,
                    new ArrayList<>(groupIds));
            if (found.size() != distinctCount(groupIds)) {
                throw new IllegalRowLimitPolicyException(
                        msg("error.row_limit_applies_group_not_in_org"));
            }
        }
    }

    private static long distinctCount(List<UUID> ids) {
        return ids.stream().distinct().count();
    }

    private static RowLimitPolicyView toView(RowLimitPolicyEntity entity) {
        return new RowLimitPolicyView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getSchemaName(),
                entity.getTableName(),
                entity.getMaxRows(),
                entity.getAppliesToRoles() == null ? List.of() : List.of(entity.getAppliesToRoles()),
                toUuidList(entity.getAppliesToGroupIds()),
                toUuidList(entity.getAppliesToUserIds()),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static List<UUID> toUuidList(UUID[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private static String[] toStringArray(List<String> values) {
        return values.isEmpty() ? null : values.toArray(new String[0]);
    }

    private static UUID[] toUuidArray(List<UUID> values) {
        return values == null || values.isEmpty() ? null : values.toArray(new UUID[0]);
    }
}
