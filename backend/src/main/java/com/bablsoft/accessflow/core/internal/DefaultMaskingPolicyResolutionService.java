package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
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
class DefaultMaskingPolicyResolutionService implements MaskingPolicyResolutionService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultMaskingPolicyResolutionService.class);
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

    private final MaskingPolicyRepository maskingPolicyRepository;
    private final UserRepository userRepository;
    private final UserGroupMembershipRepository membershipRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedColumnMask> resolveApplicable(UUID organizationId, UUID datasourceId,
                                                       UUID requesterUserId) {
        var policies = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        if (policies.isEmpty()) {
            return List.of();
        }
        var roleName = userRepository.findById(requesterUserId)
                .map(u -> u.roleName())
                .orElse(null);
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        var resolved = new ArrayList<ResolvedColumnMask>();
        for (var policy : policies) {
            if (isRevealed(policy, requesterUserId, roleName, groupIds)) {
                continue;
            }
            resolved.add(new ResolvedColumnMask(policy.getId(), policy.getColumnRef(),
                    policy.getStrategy(), parseParams(policy.getStrategyParams())));
        }
        return resolved;
    }

    private static boolean isRevealed(MaskingPolicyEntity policy, UUID userId, String roleName,
                                      Set<UUID> groupIds) {
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
            log.warn("Failed to parse masking strategy_params JSON, treating as empty: {}",
                    ex.getMessage());
            return Map.of();
        }
    }
}
