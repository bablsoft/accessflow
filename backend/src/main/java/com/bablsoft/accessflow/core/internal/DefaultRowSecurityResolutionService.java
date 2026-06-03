package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowSecurityPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowSecurityPolicyRepository;
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
class DefaultRowSecurityResolutionService implements RowSecurityResolutionService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultRowSecurityResolutionService.class);
    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() {};

    private final RowSecurityPolicyRepository rowSecurityPolicyRepository;
    private final UserRepository userRepository;
    private final UserGroupMembershipRepository membershipRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedRowSecurityPredicate> resolveApplicable(UUID organizationId,
                                                                UUID datasourceId,
                                                                UUID requesterUserId) {
        var policies = rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        if (policies.isEmpty()) {
            return List.of();
        }
        var user = userRepository.findById(requesterUserId).orElse(null);
        var role = user != null ? user.getRole() : null;
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        var resolved = new ArrayList<ResolvedRowSecurityPredicate>();
        for (var policy : policies) {
            if (!appliesTo(policy, requesterUserId, role, groupIds)) {
                continue;
            }
            var values = resolveValues(policy, requesterUserId, user, role);
            resolved.add(new ResolvedRowSecurityPredicate(policy.getId(), policy.getTableName(),
                    policy.getColumnName(), policy.getOperator(), values));
        }
        return resolved;
    }

    private static boolean appliesTo(RowSecurityPolicyEntity policy, UUID userId, UserRoleType role,
                                     Set<UUID> groupIds) {
        boolean hasRoles = policy.getAppliesToRoles() != null && policy.getAppliesToRoles().length > 0;
        boolean hasGroups =
                policy.getAppliesToGroupIds() != null && policy.getAppliesToGroupIds().length > 0;
        boolean hasUsers =
                policy.getAppliesToUserIds() != null && policy.getAppliesToUserIds().length > 0;
        if (!hasRoles && !hasGroups && !hasUsers) {
            return true; // empty scope = applies to every submitter (governance-safe default)
        }
        if (hasRoles && role != null) {
            for (var allowed : policy.getAppliesToRoles()) {
                if (allowed != null && role.name().equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        if (hasUsers) {
            for (var allowed : policy.getAppliesToUserIds()) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        if (hasGroups && !groupIds.isEmpty()) {
            for (var allowed : policy.getAppliesToGroupIds()) {
                if (groupIds.contains(allowed)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolves the policy's value source to concrete bound value(s). LITERALs return their single
     * value. VARIABLEs resolve from the submitter's built-ins ({@code user.id} / {@code user.email}
     * / {@code user.role} / {@code user.groups}) or {@code users.attributes}. An unresolvable
     * variable returns an empty list — the fail-closed deny signal the rewriter turns into an
     * always-false predicate.
     */
    private List<Object> resolveValues(RowSecurityPolicyEntity policy, UUID userId, UserEntity user,
                                       UserRoleType role) {
        if (policy.getValueType() == RowSecurityValueType.LITERAL) {
            return List.of(policy.getValueExpression());
        }
        var variable = policy.getValueExpression();
        return switch (variable) {
            case "user.id" -> List.of(userId.toString());
            case "user.email" -> user != null && user.getEmail() != null
                    ? List.of(user.getEmail()) : List.of();
            case "user.role" -> role != null ? List.of(role.name()) : List.of();
            case "user.groups" -> new ArrayList<>(membershipRepository.findGroupNamesForUser(userId));
            default -> resolveAttribute(user, variable.substring("user.".length()));
        };
    }

    private List<Object> resolveAttribute(UserEntity user, String key) {
        if (user == null) {
            return List.of();
        }
        var value = parseAttributes(user.getAttributes()).get(key);
        return value == null ? List.of() : List.of(value);
    }

    private Map<String, String> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, ATTR_TYPE);
            var out = new LinkedHashMap<String, String>();
            raw.forEach((key, value) -> {
                if (value != null) {
                    out.put(key, String.valueOf(value));
                }
            });
            return out;
        } catch (RuntimeException ex) {
            log.warn("Failed to parse users.attributes JSON, treating as empty: {}", ex.getMessage());
            return Map.of();
        }
    }
}
