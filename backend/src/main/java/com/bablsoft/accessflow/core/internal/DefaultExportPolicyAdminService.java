package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateExportPolicyCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.ExportPolicyAdminService;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.api.IllegalExportPolicyException;
import com.bablsoft.accessflow.core.api.UpdateExportPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.ExportPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ExportPolicyRepository;
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
class DefaultExportPolicyAdminService implements ExportPolicyAdminService {

    static final int MAX_ROW_CAP = 1_000_000;

    private final ExportPolicyRepository exportPolicyRepository;
    private final RoleRepository roleRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<ExportPolicyView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return exportPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(organizationId, datasourceId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public ExportPolicyView create(UUID datasourceId, UUID organizationId,
                                   CreateExportPolicyCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var mode = requireMode(command.mode());
        var rowCap = validateRowCap(mode, command.rowCap());
        var denyClassifications = validateDenyClassifications(mode, command.denyClassifications());
        var roles = normalizeRoles(organizationId, command.appliesToRoles());
        validateAppliesToTargets(organizationId, command.appliesToUserIds(), command.appliesToGroupIds());

        var entity = new ExportPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        entity.setMode(mode);
        entity.setRowCap(rowCap);
        entity.setDenyClassifications(toClassificationArray(denyClassifications));
        entity.setAppliesToRoles(toStringArray(roles));
        entity.setAppliesToGroupIds(toUuidArray(command.appliesToGroupIds()));
        entity.setAppliesToUserIds(toUuidArray(command.appliesToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(exportPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public ExportPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                                   UpdateExportPolicyCommand command) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        var mode = requireMode(command.mode());
        var rowCap = validateRowCap(mode, command.rowCap());
        var denyClassifications = validateDenyClassifications(mode, command.denyClassifications());
        var roles = normalizeRoles(organizationId, command.appliesToRoles());
        validateAppliesToTargets(organizationId, command.appliesToUserIds(), command.appliesToGroupIds());

        entity.setMode(mode);
        entity.setRowCap(rowCap);
        entity.setDenyClassifications(toClassificationArray(denyClassifications));
        entity.setAppliesToRoles(toStringArray(roles));
        entity.setAppliesToGroupIds(toUuidArray(command.appliesToGroupIds()));
        entity.setAppliesToUserIds(toUuidArray(command.appliesToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(exportPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID datasourceId, UUID organizationId) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        exportPolicyRepository.delete(entity);
    }

    private ExportPolicyEntity loadInScope(UUID policyId, UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = exportPolicyRepository.findByIdAndOrganizationId(policyId, organizationId)
                .orElseThrow(() -> new ExportPolicyNotFoundException(policyId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new ExportPolicyNotFoundException(policyId);
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

    private ExportPolicyMode requireMode(ExportPolicyMode mode) {
        if (mode == null) {
            throw new IllegalExportPolicyException(msg("error.export_policy_mode_required"));
        }
        return mode;
    }

    /**
     * {@code row_cap} is required (1..{@value #MAX_ROW_CAP}) for {@code ROW_CAP} and rejected for
     * every other mode — a cap on an ALLOW/WATERMARK/DENY_CLASSIFIED row would silently never
     * apply, which reads as governance that is not there.
     */
    private Integer validateRowCap(ExportPolicyMode mode, Integer rowCap) {
        if (mode == ExportPolicyMode.ROW_CAP) {
            if (rowCap == null) {
                throw new IllegalExportPolicyException(msg("error.export_policy_row_cap_required"));
            }
            if (rowCap < 1 || rowCap > MAX_ROW_CAP) {
                throw new IllegalExportPolicyException(
                        msg("error.export_policy_row_cap_range", MAX_ROW_CAP));
            }
            return rowCap;
        }
        if (rowCap != null) {
            throw new IllegalExportPolicyException(msg("error.export_policy_row_cap_not_allowed"));
        }
        return null;
    }

    private List<DataClassification> validateDenyClassifications(
            ExportPolicyMode mode, List<DataClassification> denyClassifications) {
        if (denyClassifications == null || denyClassifications.isEmpty()) {
            return List.of();
        }
        if (mode != ExportPolicyMode.DENY_CLASSIFIED) {
            throw new IllegalExportPolicyException(
                    msg("error.export_policy_deny_classifications_not_allowed"));
        }
        return denyClassifications.stream().distinct().toList();
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
                    .orElseThrow(() -> new IllegalExportPolicyException(
                            msg("error.export_policy_unknown_role", role)));
            normalized.add(resolved.getName());
        }
        return normalized;
    }

    private void validateAppliesToTargets(UUID organizationId, List<UUID> userIds,
                                          List<UUID> groupIds) {
        if (userIds != null && !userIds.isEmpty()) {
            var found = userRepository.findAllByOrganization_IdAndIdIn(organizationId, userIds);
            if (found.size() != distinctCount(userIds)) {
                throw new IllegalExportPolicyException(
                        msg("error.export_policy_applies_user_not_in_org"));
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var found = userGroupRepository.findAllByOrganization_IdAndIdIn(organizationId,
                    new ArrayList<>(groupIds));
            if (found.size() != distinctCount(groupIds)) {
                throw new IllegalExportPolicyException(
                        msg("error.export_policy_applies_group_not_in_org"));
            }
        }
    }

    private static long distinctCount(List<UUID> ids) {
        return ids.stream().distinct().count();
    }

    private ExportPolicyView toView(ExportPolicyEntity entity) {
        return new ExportPolicyView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getMode(),
                entity.getRowCap(),
                toClassificationList(entity.getDenyClassifications()),
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

    private static String[] toClassificationArray(List<DataClassification> values) {
        return values == null || values.isEmpty() ? null
                : values.stream().map(Enum::name).toArray(String[]::new);
    }

    static List<DataClassification> toClassificationList(String[] values) {
        return values == null ? List.of()
                : List.of(values).stream().map(DataClassification::valueOf).toList();
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
