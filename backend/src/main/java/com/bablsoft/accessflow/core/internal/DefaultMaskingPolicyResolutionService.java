package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.MaskingExplanation;
import com.bablsoft.accessflow.core.api.MaskingPolicyDraft;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.RevealedColumnMask;
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

    /**
     * Synthetic id stamped on a simulated draft that does not replace an existing policy, so the
     * simulator can tell the draft's mask apart from the persisted ones it is compared against.
     */
    public static final UUID DRAFT_POLICY_ID = new UUID(0L, 0L);

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedColumnMask> resolveApplicable(UUID organizationId, UUID datasourceId,
                                                       UUID requesterUserId) {
        return resolve(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId),
                requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedColumnMask> resolveWithDraft(UUID organizationId, UUID datasourceId,
                                                     UUID requesterUserId, MaskingPolicyDraft draft) {
        var persisted = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId);
        var candidate = new ArrayList<MaskingPolicyEntity>(persisted.size() + 1);
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
    public MaskingExplanation explain(UUID organizationId, UUID datasourceId,
                                      UUID requesterUserId) {
        return explain(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId),
                requesterUserId);
    }

    private List<ResolvedColumnMask> resolve(List<MaskingPolicyEntity> policies,
                                             UUID requesterUserId) {
        return explain(policies, requesterUserId).applied();
    }

    private MaskingExplanation explain(List<MaskingPolicyEntity> policies, UUID requesterUserId) {
        if (policies.isEmpty()) {
            return new MaskingExplanation(List.of(), List.of());
        }
        var roleName = userRepository.findById(requesterUserId)
                .map(u -> u.roleName())
                .orElse(null);
        var groupIds = new HashSet<>(membershipRepository.findGroupIdsForUser(requesterUserId));
        var applied = new ArrayList<ResolvedColumnMask>();
        var revealed = new ArrayList<RevealedColumnMask>();
        for (var policy : policies) {
            var reasons = AppliesToMatcher.revealReasons(policy.getRevealToRoles(),
                    policy.getRevealToGroupIds(), policy.getRevealToUserIds(), requesterUserId,
                    roleName, groupIds);
            if (!reasons.isEmpty()) {
                revealed.add(new RevealedColumnMask(policy.getId(), policy.getColumnRef(),
                        policy.getStrategy(), reasons));
                continue;
            }
            applied.add(new ResolvedColumnMask(policy.getId(), policy.getColumnRef(),
                    policy.getStrategy(), parseParams(policy.getStrategyParams())));
        }
        return new MaskingExplanation(applied, revealed);
    }

    /**
     * A detached, never-persisted entity carrying the draft's fields, so reveal matching runs
     * through the exact same code the saved path uses. It is never handed to a repository.
     */
    private MaskingPolicyEntity toTransientEntity(MaskingPolicyDraft draft) {
        var entity = new MaskingPolicyEntity();
        entity.setId(draft.replacesPolicyId() != null ? draft.replacesPolicyId() : DRAFT_POLICY_ID);
        entity.setColumnRef(draft.columnRef());
        entity.setStrategy(draft.strategy());
        entity.setStrategyParams(objectMapper.writeValueAsString(draft.strategyParams()));
        entity.setRevealToRoles(draft.revealToRoles().toArray(String[]::new));
        entity.setRevealToGroupIds(draft.revealToGroupIds().toArray(UUID[]::new));
        entity.setRevealToUserIds(draft.revealToUserIds().toArray(UUID[]::new));
        entity.setEnabled(true);
        return entity;
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
