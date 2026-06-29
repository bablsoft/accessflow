package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultLifecycleDirectiveResolutionService implements LifecycleDirectiveResolutionService {

    private final RetentionPolicyRepository policyRepository;
    private final LifecycleSaltService saltService;

    @Override
    @Transactional(readOnly = true)
    public List<ColumnMaskDirective> resolveColumnMasks(UUID organizationId, UUID datasourceId) {
        var policies = policyRepository.findAllByDatasourceIdAndEnabledTrue(datasourceId).stream()
                .filter(p -> p.getAction() == LifecycleAction.PSEUDONYMIZE
                        && p.getTransformType() != null
                        && p.getTargetColumns() != null && p.getTargetColumns().length > 0)
                .toList();
        if (policies.isEmpty()) {
            return List.of();
        }
        // Fetch the per-org salt lazily — only when a salted transform is actually present.
        String salt = needsSalt(policies) ? saltService.currentSalt(organizationId) : null;
        var directives = new ArrayList<ColumnMaskDirective>();
        for (RetentionPolicyEntity policy : policies) {
            var strategy = strategyFor(policy.getTransformType());
            var params = paramsFor(policy.getTransformType(), salt);
            for (String column : policy.getTargetColumns()) {
                directives.add(new ColumnMaskDirective(columnRef(policy.getTargetTable(), column),
                        strategy, params, policy.getId()));
            }
        }
        return directives;
    }

    private static boolean needsSalt(List<RetentionPolicyEntity> policies) {
        return policies.stream().anyMatch(p ->
                p.getTransformType() == LifecycleTransform.SHA256_SALTED
                        || p.getTransformType() == LifecycleTransform.TOKENIZATION);
    }

    private static String columnRef(String table, String column) {
        return table == null || table.isBlank() ? column : table + "." + column;
    }

    private static MaskingStrategy strategyFor(LifecycleTransform transform) {
        return switch (transform) {
            // Both SHA256_SALTED and TOKENIZATION render as an irreversible deterministic token via a
            // salted SHA-256 — the salt parameter distinguishes them from a plain HASH masking policy.
            case SHA256_SALTED, TOKENIZATION -> MaskingStrategy.HASH;
            case FORMAT_PRESERVING -> MaskingStrategy.FORMAT_PRESERVING;
        };
    }

    private static Map<String, String> paramsFor(LifecycleTransform transform, String salt) {
        return switch (transform) {
            case SHA256_SALTED -> salt == null ? Map.of() : Map.of("salt", salt);
            case TOKENIZATION -> salt == null ? Map.of() : Map.of("salt", "tok:" + salt);
            case FORMAT_PRESERVING -> Map.of();
        };
    }
}
