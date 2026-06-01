package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalMaskingPolicyException;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.MaskingPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.MaskingPolicyView;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UpdateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultMaskingPolicyAdminService implements MaskingPolicyAdminService {

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};
    private static final int MAX_VISIBLE_SUFFIX = 256;

    private final MaskingPolicyRepository maskingPolicyRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<MaskingPolicyView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(organizationId, datasourceId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public MaskingPolicyView create(UUID datasourceId, UUID organizationId,
                                    CreateMaskingPolicyCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var columnRef = requireColumnRef(command.columnRef());
        var strategy = requireStrategy(command.strategy());
        validateParams(strategy, command.strategyParams());
        var roles = normalizeRoles(command.revealToRoles());
        validateRevealTargets(organizationId, command.revealToUserIds(), command.revealToGroupIds());

        var entity = new MaskingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        entity.setColumnRef(columnRef);
        entity.setStrategy(strategy);
        entity.setStrategyParams(serializeParams(command.strategyParams()));
        entity.setRevealToRoles(toStringArray(roles));
        entity.setRevealToGroupIds(toUuidArray(command.revealToGroupIds()));
        entity.setRevealToUserIds(toUuidArray(command.revealToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(maskingPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public MaskingPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                                    UpdateMaskingPolicyCommand command) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        var columnRef = requireColumnRef(command.columnRef());
        var strategy = requireStrategy(command.strategy());
        validateParams(strategy, command.strategyParams());
        var roles = normalizeRoles(command.revealToRoles());
        validateRevealTargets(organizationId, command.revealToUserIds(), command.revealToGroupIds());

        entity.setColumnRef(columnRef);
        entity.setStrategy(strategy);
        entity.setStrategyParams(serializeParams(command.strategyParams()));
        entity.setRevealToRoles(toStringArray(roles));
        entity.setRevealToGroupIds(toUuidArray(command.revealToGroupIds()));
        entity.setRevealToUserIds(toUuidArray(command.revealToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(maskingPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID datasourceId, UUID organizationId) {
        var entity = loadInScope(policyId, datasourceId, organizationId);
        maskingPolicyRepository.delete(entity);
    }

    private MaskingPolicyEntity loadInScope(UUID policyId, UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = maskingPolicyRepository.findByIdAndOrganizationId(policyId, organizationId)
                .orElseThrow(() -> new MaskingPolicyNotFoundException(policyId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new MaskingPolicyNotFoundException(policyId);
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

    private String requireColumnRef(String columnRef) {
        if (columnRef == null || columnRef.isBlank()) {
            throw new IllegalMaskingPolicyException(msg("error.masking_policy_column_required"));
        }
        return columnRef.trim();
    }

    private MaskingStrategy requireStrategy(MaskingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalMaskingPolicyException(msg("error.masking_policy_strategy_required"));
        }
        return strategy;
    }

    private void validateParams(MaskingStrategy strategy, Map<String, String> params) {
        if (strategy != MaskingStrategy.PARTIAL || params == null) {
            return;
        }
        var suffix = params.get("visible_suffix");
        if (suffix == null || suffix.isBlank()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(suffix.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalMaskingPolicyException(msg("error.masking_policy_invalid_visible_suffix"));
        }
        if (value < 1 || value > MAX_VISIBLE_SUFFIX) {
            throw new IllegalMaskingPolicyException(msg("error.masking_policy_invalid_visible_suffix"));
        }
    }

    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        var normalized = new ArrayList<String>(roles.size());
        for (var role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            try {
                normalized.add(UserRoleType.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT)).name());
            } catch (IllegalArgumentException ex) {
                throw new IllegalMaskingPolicyException(
                        msg("error.masking_policy_unknown_role", role));
            }
        }
        return normalized;
    }

    private void validateRevealTargets(UUID organizationId, List<UUID> userIds, List<UUID> groupIds) {
        if (userIds != null && !userIds.isEmpty()) {
            var found = userRepository.findAllByOrganization_IdAndIdIn(organizationId, userIds);
            if (found.size() != distinctCount(userIds)) {
                throw new IllegalMaskingPolicyException(
                        msg("error.masking_policy_reveal_user_not_in_org"));
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var found = userGroupRepository.findAllByOrganization_IdAndIdIn(organizationId,
                    new ArrayList<>(groupIds));
            if (found.size() != distinctCount(groupIds)) {
                throw new IllegalMaskingPolicyException(
                        msg("error.masking_policy_reveal_group_not_in_org"));
            }
        }
    }

    private static long distinctCount(List<UUID> ids) {
        return ids.stream().distinct().count();
    }

    private String serializeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(params);
    }

    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, PARAMS_TYPE);
            var out = new LinkedHashMap<String, String>();
            raw.forEach((key, value) -> {
                if (value != null) {
                    out.put(key, String.valueOf(value));
                }
            });
            return out;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private MaskingPolicyView toView(MaskingPolicyEntity entity) {
        return new MaskingPolicyView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getColumnRef(),
                entity.getStrategy(),
                parseParams(entity.getStrategyParams()),
                toStringList(entity.getRevealToRoles()),
                toUuidList(entity.getRevealToGroupIds()),
                toUuidList(entity.getRevealToUserIds()),
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
