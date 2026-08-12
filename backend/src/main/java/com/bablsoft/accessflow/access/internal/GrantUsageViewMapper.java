package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Maps the persisted grant-usage summary onto the module's exposed read model (#625). */
@Component
@RequiredArgsConstructor
class GrantUsageViewMapper {

    private static final Logger log = LoggerFactory.getLogger(GrantUsageViewMapper.class);

    private final ObjectMapper objectMapper;

    GrantUsageView toView(GrantUsageSummaryEntity entity) {
        return new GrantUsageView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getResourceKind(),
                entity.getResourceId(),
                entity.getResourceName(),
                entity.getPermissionId(),
                entity.getUserId(),
                entity.getUserEmail(),
                entity.getUserDisplayName(),
                entity.getGrantedAt(),
                entity.getExpiresAt(),
                entity.getGrantedTargetCount(),
                readUsedTargets(entity),
                entity.getUsedTargetCount(),
                entity.getUsageCount(),
                entity.getFirstUsedAt(),
                entity.getLastUsedAt(),
                entity.getObservedSince(),
                entity.getRecommendation());
    }

    /**
     * The stored target list. A row whose JSON will not parse degrades to an empty list rather than
     * failing the read — the summary is a derived cache, and the next aggregation tick rewrites it.
     */
    List<String> readUsedTargets(GrantUsageSummaryEntity entity) {
        var raw = entity.getUsedTargets();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            var node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            var targets = new ArrayList<String>(node.size());
            node.forEach(element -> {
                if (element.isString()) {
                    targets.add(element.asString());
                }
            });
            return targets;
        } catch (RuntimeException ex) {
            log.warn("Grant usage summary {} has unreadable used_targets; treating as empty",
                    entity.getId());
            return List.of();
        }
    }

    /** Serializes the exercised-target set for persistence. */
    String writeUsedTargets(List<String> targets) {
        return objectMapper.writeValueAsString(targets == null ? List.of() : targets);
    }
}
