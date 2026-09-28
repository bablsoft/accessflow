package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AccessTargetMatch;
import com.bablsoft.accessflow.core.api.ExplainedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyDraft;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
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

    /**
     * Synthetic id stamped on a simulated draft that does not replace an existing policy, so the
     * simulator can tell the draft's predicate apart from the persisted ones it is compared against.
     */
    public static final UUID DRAFT_POLICY_ID = new UUID(0L, 0L);

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedRowSecurityPredicate> resolveApplicable(UUID organizationId,
                                                                UUID datasourceId,
                                                                UUID requesterUserId) {
        return resolve(rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId),
                requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedRowSecurityPredicate> resolveWithDraft(UUID organizationId,
                                                               UUID datasourceId,
                                                               UUID requesterUserId,
                                                               RowSecurityPolicyDraft draft) {
        var persisted = rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        var candidate = new ArrayList<RowSecurityPolicyEntity>(persisted.size() + 1);
        for (var policy : persisted) {
            if (draft == null || !policy.getId().equals(draft.replacesPolicyId())) {
                candidate.add(policy);
            }
        }
        if (draft != null && draft.enabled()) {
            candidate.add(toTransientEntity(draft));
        }
        return resolve(candidate, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExplainedRowSecurityPredicate> explainApplicable(UUID organizationId,
                                                                 UUID datasourceId,
                                                                 UUID requesterUserId) {
        return explain(rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId),
                requesterUserId);
    }

    private List<ResolvedRowSecurityPredicate> resolve(List<RowSecurityPolicyEntity> policies,
                                                       UUID requesterUserId) {
        return explain(policies, requesterUserId).stream()
                .map(ExplainedRowSecurityPredicate::predicate)
                .toList();
    }

    private List<ExplainedRowSecurityPredicate> explain(List<RowSecurityPolicyEntity> policies,
                                                        UUID requesterUserId) {
        if (policies.isEmpty()) {
            return List.of();
        }
        var user = userRepository.findById(requesterUserId).orElse(null);
        var roleName = user != null ? user.roleName() : null;
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        var resolved = new ArrayList<ExplainedRowSecurityPredicate>();
        for (var policy : policies) {
            var matchedBy = targets(policy, requesterUserId, roleName, groupIds);
            if (matchedBy.isEmpty()) {
                continue;
            }
            var values = resolveValues(policy, requesterUserId, user, roleName);
            resolved.add(new ExplainedRowSecurityPredicate(
                    new ResolvedRowSecurityPredicate(policy.getId(), policy.getTableName(),
                            policy.getColumnName(), policy.getOperator(), values),
                    policy.getValueType(), policy.getValueExpression(), matchedBy));
        }
        return resolved;
    }

    /**
     * A detached, never-persisted entity carrying the draft's fields, so scope matching and value
     * resolution run through the exact same code the saved path uses — a second implementation
     * would be free to drift from the one that actually governs queries. It is never handed to a
     * repository.
     */
    private static RowSecurityPolicyEntity toTransientEntity(RowSecurityPolicyDraft draft) {
        var entity = new RowSecurityPolicyEntity();
        entity.setId(draft.replacesPolicyId() != null ? draft.replacesPolicyId() : DRAFT_POLICY_ID);
        entity.setTableName(draft.tableName());
        entity.setColumnName(draft.columnName());
        entity.setOperator(draft.operator());
        entity.setValueType(draft.valueType());
        entity.setValueExpression(draft.valueExpression());
        entity.setAppliesToRoles(draft.appliesToRoles().toArray(String[]::new));
        entity.setAppliesToGroupIds(draft.appliesToGroupIds().toArray(UUID[]::new));
        entity.setAppliesToUserIds(draft.appliesToUserIds().toArray(UUID[]::new));
        entity.setEnabled(true);
        return entity;
    }

    private static List<AccessTargetMatch> targets(RowSecurityPolicyEntity policy, UUID userId,
                                                   String roleName, Set<UUID> groupIds) {
        return AppliesToMatcher.explain(policy.getAppliesToRoles(), policy.getAppliesToGroupIds(),
                policy.getAppliesToUserIds(), userId, roleName, groupIds);
    }

    /**
     * Resolves the policy's value source to concrete bound value(s). LITERALs return their single
     * value. VARIABLEs resolve from the submitter's built-ins ({@code user.id} / {@code user.email}
     * / {@code user.role} / {@code user.groups}) or {@code users.attributes}. An unresolvable
     * variable returns an empty list — the fail-closed deny signal the rewriter turns into an
     * always-false predicate.
     */
    private List<Object> resolveValues(RowSecurityPolicyEntity policy, UUID userId, UserEntity user,
                                       String roleName) {
        if (policy.getValueType() == RowSecurityValueType.LITERAL) {
            return List.of(policy.getValueExpression());
        }
        var variable = policy.getValueExpression();
        return switch (variable) {
            case "user.id" -> List.of(userId.toString());
            case "user.email" -> user != null && user.getEmail() != null
                    ? List.of(user.getEmail()) : List.of();
            case "user.role" -> roleName != null ? List.of(roleName) : List.of();
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
