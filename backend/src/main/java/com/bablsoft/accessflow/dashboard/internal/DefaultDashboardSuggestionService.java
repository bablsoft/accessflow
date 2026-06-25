package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.ai.api.OptimizationSuggestion;
import com.bablsoft.accessflow.core.api.MyOptimizationSourceView;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestion;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionStatus;
import com.bablsoft.accessflow.dashboard.api.InvalidSuggestionIdException;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardSuggestionStateEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardSuggestionStateRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a user's AI optimization-suggestion backlog (AF-498) from their recent
 * {@code ai_analyses.optimizations[]} and the per-item dismissal/applied state persisted in
 * {@code dashboard_suggestion_state}. A suggestion is implicitly OPEN unless a state row says
 * otherwise; only OPEN items appear in the backlog. Self-scoped throughout.
 */
@Service
@RequiredArgsConstructor
class DefaultDashboardSuggestionService implements DashboardSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDashboardSuggestionService.class);

    /** Number of the user's most recent analyses scanned for suggestions. */
    static final int SOURCE_LIMIT = 100;

    private final MyQueryInsightsLookupService insightsLookupService;
    private final DashboardSuggestionStateRepository stateRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<DashboardSuggestion> listOpen(UUID organizationId, UUID userId) {
        var sources = insightsLookupService.recentOptimizationSources(organizationId, userId, SOURCE_LIMIT);
        if (sources.isEmpty()) {
            return List.of();
        }
        var overrides = loadOverrides(organizationId, userId, sources);
        var result = new ArrayList<DashboardSuggestion>();
        for (var source : sources) {
            var items = parse(source.optimizationsJson());
            for (int i = 0; i < items.size(); i++) {
                var status = overrides.getOrDefault(stateKey(source.aiAnalysisId(), i),
                        DashboardSuggestionStatus.OPEN);
                if (status == DashboardSuggestionStatus.OPEN) {
                    result.add(toSuggestion(source, items.get(i), i));
                }
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpen(UUID organizationId, UUID userId) {
        return listOpen(organizationId, userId).size();
    }

    @Override
    @Transactional
    public void dismiss(UUID organizationId, UUID userId, String id) {
        var ref = SuggestionId.parse(id);
        var source = insightsLookupService
                .recentOptimizationSources(organizationId, userId, SOURCE_LIMIT).stream()
                .filter(s -> s.aiAnalysisId().equals(ref.aiAnalysisId()))
                .findFirst()
                .orElseThrow(() -> new InvalidSuggestionIdException(id));
        var items = parse(source.optimizationsJson());
        if (ref.index() < 0 || ref.index() >= items.size()) {
            throw new InvalidSuggestionIdException(id);
        }
        upsertState(organizationId, userId, ref.aiAnalysisId(), ref.index(),
                DashboardSuggestionStatus.DISMISSED);
    }

    private Map<String, DashboardSuggestionStatus> loadOverrides(UUID organizationId, UUID userId,
                                                                 List<MyOptimizationSourceView> sources) {
        var analysisIds = sources.stream().map(MyOptimizationSourceView::aiAnalysisId).toList();
        return stateRepository
                .findByOrganizationIdAndUserIdAndAiAnalysisIdIn(organizationId, userId, analysisIds)
                .stream()
                .collect(Collectors.toMap(
                        e -> stateKey(e.getAiAnalysisId(), e.getSuggestionIndex()),
                        DashboardSuggestionStateEntity::getStatus,
                        (a, b) -> b));
    }

    private void upsertState(UUID organizationId, UUID userId, UUID aiAnalysisId, int index,
                             DashboardSuggestionStatus status) {
        var now = clock.instant();
        var existing = stateRepository
                .findByOrganizationIdAndUserIdAndAiAnalysisIdAndSuggestionIndex(
                        organizationId, userId, aiAnalysisId, index)
                .orElse(null);
        if (existing == null) {
            var entity = new DashboardSuggestionStateEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setUserId(userId);
            entity.setAiAnalysisId(aiAnalysisId);
            entity.setSuggestionIndex(index);
            entity.setStatus(status);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            stateRepository.save(entity);
        } else if (existing.getStatus() != status) {
            existing.setStatus(status);
            existing.setUpdatedAt(now);
            stateRepository.save(existing);
        }
    }

    private List<OptimizationSuggestion> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, OptimizationSuggestion[].class));
        } catch (RuntimeException ex) {
            log.warn("Unparseable optimizations JSON, skipping: {}", ex.getMessage());
            return List.of();
        }
    }

    private static DashboardSuggestion toSuggestion(MyOptimizationSourceView source,
                                                    OptimizationSuggestion item, int index) {
        return new DashboardSuggestion(
                stateKey(source.aiAnalysisId(), index),
                source.aiAnalysisId(),
                index,
                source.queryRequestId(),
                source.datasourceId(),
                source.datasourceName(),
                source.dbType(),
                source.riskLevel(),
                item.type(),
                item.title(),
                item.rationale(),
                item.sql(),
                DashboardSuggestionStatus.OPEN,
                source.createdAt());
    }

    private static String stateKey(UUID aiAnalysisId, int index) {
        return aiAnalysisId + ":" + index;
    }

    /** The {@code {aiAnalysisId}:{index}} handle parsed back into its parts. */
    private record SuggestionId(UUID aiAnalysisId, int index) {
        static SuggestionId parse(String id) {
            if (id == null) {
                throw new InvalidSuggestionIdException(null);
            }
            int sep = id.lastIndexOf(':');
            if (sep <= 0 || sep == id.length() - 1) {
                throw new InvalidSuggestionIdException(id);
            }
            try {
                return new SuggestionId(UUID.fromString(id.substring(0, sep)),
                        Integer.parseInt(id.substring(sep + 1)));
            } catch (IllegalArgumentException ex) {
                throw new InvalidSuggestionIdException(id);
            }
        }
    }
}
