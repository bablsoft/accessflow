package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorBaselineRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Loads the rolling {@link BaselineProfile} for a subject and folds completed windows into it,
 * persisting to {@code behavior_baseline}. The profile is stored as a JSONB blob via the AI module's
 * {@code ObjectMapper}; unparseable / legacy JSON degrades to an empty profile (the baseline
 * self-heals as new windows fold in).
 */
@Service
@RequiredArgsConstructor
class DefaultBehaviorBaselineService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBehaviorBaselineService.class);

    private final BehaviorBaselineRepository baselineRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Load the subject's baseline; creates a transient (unsaved) empty profile when none exists. */
    BaselineState load(UUID organizationId, UUID userId, UUID datasourceId) {
        var entity = baselineRepository
                .findByOrganizationIdAndUserIdAndDatasourceId(organizationId, userId, datasourceId)
                .orElse(null);
        if (entity == null) {
            entity = new BehaviorBaselineEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setUserId(userId);
            entity.setDatasourceId(datasourceId);
            return new BaselineState(entity, BaselineProfile.empty());
        }
        return new BaselineState(entity, parse(entity.getFeatures()));
    }

    /** True when this window has already been folded into the baseline (idempotency guard). */
    boolean alreadyFolded(BaselineState state, Instant windowStart) {
        var last = state.entity().getLastWindowStart();
        return last != null && !windowStart.isAfter(last);
    }

    /** Fold the window into the profile and persist; trims each scalar list to {@code maxSamples}. */
    void fold(BaselineState state, WindowFeatures window, Instant windowStart, int maxSamples) {
        var folded = state.profile().fold(window, maxSamples);
        var entity = state.entity();
        entity.setFeatures(serialize(folded));
        entity.setSampleSize(folded.windowsFolded());
        entity.setLastWindowStart(windowStart);
        entity.setLastRefreshedAt(clock.instant());
        baselineRepository.save(entity);
    }

    private BaselineProfile parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return BaselineProfile.empty();
        }
        try {
            return objectMapper.readValue(json, BaselineProfile.class);
        } catch (RuntimeException ex) {
            log.warn("Unparseable baseline profile; resetting to empty: {}", ex.getMessage());
            return BaselineProfile.empty();
        }
    }

    private String serialize(BaselineProfile profile) {
        return objectMapper.writeValueAsString(profile);
    }
}
