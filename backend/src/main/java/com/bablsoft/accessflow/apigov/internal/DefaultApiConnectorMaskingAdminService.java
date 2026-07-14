package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.RoleLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorMaskingPolicyException;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorMaskingAdminService implements ApiConnectorMaskingAdminService {

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};
    private static final int MAX_VISIBLE_SUFFIX = 256;

    private final ApiConnectorMaskingPolicyRepository policyRepository;
    private final RoleLookupService roleLookupService;
    private final ApiConnectorRepository connectorRepository;
    private final UserQueryService userQueryService;
    private final UserGroupService userGroupService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorMaskingPolicyView> listForConnector(UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        return policyRepository
                .findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(organizationId, connectorId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public ApiConnectorMaskingPolicyView create(UUID connectorId, UUID organizationId,
                                                CreateApiConnectorMaskingPolicyCommand command) {
        requireConnectorInOrganization(connectorId, organizationId);
        var matcherType = requireMatcherType(command.matcherType());
        var operationId = requireOperationFor(matcherType, command.operationId());
        var fieldRef = requireFieldRef(command.fieldRef());
        var strategy = requireStrategy(command.strategy());
        validateParams(strategy, command.strategyParams());
        var roles = normalizeRoles(organizationId, command.revealToRoles());
        validateRevealTargets(organizationId, command.revealToUserIds(), command.revealToGroupIds());

        var entity = new ApiConnectorMaskingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setConnectorId(connectorId);
        entity.setMatcherType(matcherType);
        entity.setOperationId(operationId);
        entity.setFieldRef(fieldRef);
        entity.setStrategy(strategy);
        entity.setStrategyParams(serializeParams(command.strategyParams()));
        entity.setRevealToRoles(toStringArray(roles));
        entity.setRevealToGroupIds(toUuidArray(command.revealToGroupIds()));
        entity.setRevealToUserIds(toUuidArray(command.revealToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(policyRepository.save(entity));
    }

    @Override
    @Transactional
    public ApiConnectorMaskingPolicyView update(UUID policyId, UUID connectorId, UUID organizationId,
                                                UpdateApiConnectorMaskingPolicyCommand command) {
        var entity = loadInScope(policyId, connectorId, organizationId);
        var matcherType = requireMatcherType(command.matcherType());
        var operationId = requireOperationFor(matcherType, command.operationId());
        var fieldRef = requireFieldRef(command.fieldRef());
        var strategy = requireStrategy(command.strategy());
        validateParams(strategy, command.strategyParams());
        var roles = normalizeRoles(organizationId, command.revealToRoles());
        validateRevealTargets(organizationId, command.revealToUserIds(), command.revealToGroupIds());

        entity.setMatcherType(matcherType);
        entity.setOperationId(operationId);
        entity.setFieldRef(fieldRef);
        entity.setStrategy(strategy);
        entity.setStrategyParams(serializeParams(command.strategyParams()));
        entity.setRevealToRoles(toStringArray(roles));
        entity.setRevealToGroupIds(toUuidArray(command.revealToGroupIds()));
        entity.setRevealToUserIds(toUuidArray(command.revealToUserIds()));
        entity.setEnabled(command.enabled() == null || command.enabled());
        return toView(policyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID connectorId, UUID organizationId) {
        policyRepository.delete(loadInScope(policyId, connectorId, organizationId));
    }

    private ApiConnectorMaskingPolicyEntity loadInScope(UUID policyId, UUID connectorId,
                                                        UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        return policyRepository.findByIdAndOrganizationIdAndConnectorId(policyId, organizationId, connectorId)
                .orElseThrow(() -> new ApiConnectorMaskingPolicyNotFoundException(policyId));
    }

    private void requireConnectorInOrganization(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private ApiMaskingMatcherType requireMatcherType(ApiMaskingMatcherType matcherType) {
        if (matcherType == null) {
            throw new IllegalApiConnectorMaskingPolicyException(msg("error.api_masking_policy_matcher_required"));
        }
        return matcherType;
    }

    private String requireOperationFor(ApiMaskingMatcherType matcherType, String operationId) {
        var trimmed = operationId == null ? null : operationId.trim();
        if (matcherType == ApiMaskingMatcherType.SCHEMA_FIELD && (trimmed == null || trimmed.isBlank())) {
            throw new IllegalApiConnectorMaskingPolicyException(
                    msg("error.api_masking_policy_operation_required"));
        }
        return trimmed == null || trimmed.isBlank() ? null : trimmed;
    }

    private String requireFieldRef(String fieldRef) {
        if (fieldRef == null || fieldRef.isBlank()) {
            throw new IllegalApiConnectorMaskingPolicyException(msg("error.api_masking_policy_field_required"));
        }
        return fieldRef.trim();
    }

    private MaskingStrategy requireStrategy(MaskingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalApiConnectorMaskingPolicyException(msg("error.api_masking_policy_strategy_required"));
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
            throw new IllegalApiConnectorMaskingPolicyException(
                    msg("error.api_masking_policy_invalid_visible_suffix"));
        }
        if (value < 1 || value > MAX_VISIBLE_SUFFIX) {
            throw new IllegalApiConnectorMaskingPolicyException(
                    msg("error.api_masking_policy_invalid_visible_suffix"));
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
            var resolved = roleLookupService.findByNameInScope(organizationId, role.trim())
                    .orElseThrow(() -> new IllegalApiConnectorMaskingPolicyException(
                            msg("error.api_masking_policy_unknown_role", role)));
            normalized.add(resolved.name());
        }
        return normalized;
    }

    private void validateRevealTargets(UUID organizationId, List<UUID> userIds, List<UUID> groupIds) {
        if (userIds != null) {
            for (var userId : userIds) {
                var inOrg = userQueryService.findById(userId)
                        .filter(u -> organizationId.equals(u.organizationId()))
                        .isPresent();
                if (!inOrg) {
                    throw new IllegalApiConnectorMaskingPolicyException(
                            msg("error.api_masking_policy_reveal_user_not_in_org"));
                }
            }
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            var orgGroupIds = new HashSet<UUID>();
            userGroupService.listAll(organizationId).forEach(g -> orgGroupIds.add(g.id()));
            for (var groupId : groupIds) {
                if (!orgGroupIds.contains(groupId)) {
                    throw new IllegalApiConnectorMaskingPolicyException(
                            msg("error.api_masking_policy_reveal_group_not_in_org"));
                }
            }
        }
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

    private ApiConnectorMaskingPolicyView toView(ApiConnectorMaskingPolicyEntity entity) {
        return new ApiConnectorMaskingPolicyView(
                entity.getId(),
                entity.getConnectorId(),
                entity.getMatcherType(),
                entity.getOperationId(),
                entity.getFieldRef(),
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
