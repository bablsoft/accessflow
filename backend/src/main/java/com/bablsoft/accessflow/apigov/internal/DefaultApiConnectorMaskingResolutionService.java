package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorMaskingResolutionService implements ApiConnectorMaskingResolutionService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultApiConnectorMaskingResolutionService.class);
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

    private final ApiConnectorMaskingPolicyRepository policyRepository;
    private final UserQueryService userQueryService;
    private final UserGroupService userGroupService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedApiMask> resolveApplicable(UUID organizationId, UUID connectorId,
                                                   UUID requesterUserId) {
        var policies = policyRepository
                .findAllByOrganizationIdAndConnectorIdAndEnabledTrue(organizationId, connectorId);
        if (policies.isEmpty()) {
            return List.of();
        }
        var roleName = userQueryService.findById(requesterUserId).map(u -> u.roleName()).orElse(null);
        var groupIds = new HashSet<>(userGroupService.findGroupIdsForUser(requesterUserId));
        var resolved = new ArrayList<ResolvedApiMask>();
        for (var policy : policies) {
            if (isRevealed(policy, requesterUserId, roleName, groupIds)) {
                continue;
            }
            resolved.add(new ResolvedApiMask(policy.getId(), policy.getMatcherType(),
                    policy.getOperationId(), policy.getFieldRef(), policy.getStrategy(),
                    parseParams(policy.getStrategyParams())));
        }
        return resolved;
    }

    private static boolean isRevealed(ApiConnectorMaskingPolicyEntity policy, UUID userId,
                                      String roleName, Set<UUID> groupIds) {
        if (roleName != null && policy.getRevealToRoles() != null) {
            for (var allowed : policy.getRevealToRoles()) {
                if (allowed != null && roleName.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        if (policy.getRevealToUserIds() != null) {
            for (var allowed : policy.getRevealToUserIds()) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        if (policy.getRevealToGroupIds() != null && !groupIds.isEmpty()) {
            for (var allowed : policy.getRevealToGroupIds()) {
                if (groupIds.contains(allowed)) {
                    return true;
                }
            }
        }
        return false;
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
            log.warn("Failed to parse API masking strategy_params JSON, treating as empty: {}",
                    ex.getMessage());
            return Map.of();
        }
    }
}
