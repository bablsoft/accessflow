package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AnomalyBadgeView;
import com.bablsoft.accessflow.ai.api.AnomalyListFilter;
import com.bablsoft.accessflow.ai.api.AnomalyNotFoundException;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyAdminService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;
import com.bablsoft.accessflow.ai.api.IllegalAnomalyStatusTransitionException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorAnomalyRepository;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence, admin operations, and cross-module reads for behavioural anomalies (UBA, AF-383).
 * Implements the public admin + lookup APIs and exposes package-private persist helpers used by the
 * detection orchestrator. Status transitions: OPEN -&gt; ACKNOWLEDGED, OPEN/ACKNOWLEDGED -&gt;
 * DISMISSED; anything else is rejected.
 */
@Service
@RequiredArgsConstructor
class DefaultBehaviorAnomalyService implements BehaviorAnomalyAdminService, BehaviorAnomalyLookupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBehaviorAnomalyService.class);

    private final BehaviorAnomalyRepository anomalyRepository;
    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ----- admin API -----

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BehaviorAnomalyView> list(UUID organizationId, AnomalyListFilter filter,
                                                  PageRequest pageRequest) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        var spec = BehaviorAnomalySpecifications.forQuery(organizationId,
                filter == null ? AnomalyListFilter.empty() : filter);
        var page = anomalyRepository.findAll(spec, toSpringPageable(pageRequest)).map(this::toView);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public BehaviorAnomalyView get(UUID organizationId, UUID anomalyId) {
        return toView(loadOrThrow(organizationId, anomalyId));
    }

    @Override
    @Transactional
    public BehaviorAnomalyView acknowledge(UUID organizationId, UUID anomalyId, UUID actorUserId) {
        var entity = loadOrThrow(organizationId, anomalyId);
        if (entity.getStatus() != BehaviorAnomalyStatus.OPEN) {
            throw new IllegalAnomalyStatusTransitionException(anomalyId, entity.getStatus(),
                    BehaviorAnomalyStatus.ACKNOWLEDGED);
        }
        entity.setStatus(BehaviorAnomalyStatus.ACKNOWLEDGED);
        entity.setAcknowledgedBy(actorUserId);
        entity.setAcknowledgedAt(clock.instant());
        return toView(anomalyRepository.save(entity));
    }

    @Override
    @Transactional
    public BehaviorAnomalyView dismiss(UUID organizationId, UUID anomalyId, UUID actorUserId) {
        var entity = loadOrThrow(organizationId, anomalyId);
        if (entity.getStatus() == BehaviorAnomalyStatus.DISMISSED) {
            throw new IllegalAnomalyStatusTransitionException(anomalyId, entity.getStatus(),
                    BehaviorAnomalyStatus.DISMISSED);
        }
        entity.setStatus(BehaviorAnomalyStatus.DISMISSED);
        entity.setAcknowledgedBy(actorUserId);
        entity.setAcknowledgedAt(clock.instant());
        return toView(anomalyRepository.save(entity));
    }

    // ----- lookup API -----

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveAnomaly(UUID organizationId, UUID userId, UUID datasourceId) {
        if (organizationId == null || userId == null || datasourceId == null) {
            return false;
        }
        return anomalyRepository.existsByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
                organizationId, userId, datasourceId, BehaviorAnomalyStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public AnomalyBadgeView badgeForUser(UUID organizationId, UUID userId) {
        return toBadge(anomalyRepository.findByOrganizationIdAndUserIdAndStatus(
                organizationId, userId, BehaviorAnomalyStatus.OPEN));
    }

    @Override
    @Transactional(readOnly = true)
    public AnomalyBadgeView badgeForUserDatasource(UUID organizationId, UUID userId, UUID datasourceId) {
        return toBadge(anomalyRepository.findByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
                organizationId, userId, datasourceId, BehaviorAnomalyStatus.OPEN));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BehaviorAnomalyView> findById(UUID organizationId, UUID anomalyId) {
        return anomalyRepository.findByIdAndOrganizationId(anomalyId, organizationId).map(this::toView);
    }

    // ----- persistence helpers (used by the detection orchestrator) -----

    boolean existsForWindow(UUID organizationId, UUID userId, UUID datasourceId, String feature,
                            Instant windowStart) {
        return anomalyRepository.existsByOrganizationIdAndUserIdAndDatasourceIdAndFeatureAndWindowStart(
                organizationId, userId, datasourceId, feature, windowStart);
    }

    @Transactional
    Optional<BehaviorAnomalyEntity> persist(UUID organizationId, UUID userId, UUID datasourceId,
                                            DetectedAnomaly anomaly, Instant windowStart,
                                            Instant windowEnd, String aiSummary) {
        var entity = new BehaviorAnomalyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setUserId(userId);
        entity.setDatasourceId(datasourceId);
        entity.setFeature(anomaly.feature());
        entity.setScore(anomaly.score());
        entity.setObservedValue(anomaly.observedValue());
        entity.setBaselineMean(anomaly.baselineMean());
        entity.setBaselineStddev(anomaly.baselineStddev());
        entity.setDetail(objectMapper.writeValueAsString(anomaly.detail()));
        entity.setAiSummary(aiSummary);
        entity.setStatus(BehaviorAnomalyStatus.OPEN);
        entity.setDetectedAt(clock.instant());
        entity.setWindowStart(windowStart);
        entity.setWindowEnd(windowEnd);
        try {
            return Optional.of(anomalyRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against another insert for the same (user,ds,feature,window) — already exists.
            log.debug("Anomaly already exists for {}/{}/{} feature={} window={}",
                    organizationId, userId, datasourceId, anomaly.feature(), windowStart);
            return Optional.empty();
        }
    }

    // ----- mapping -----

    private BehaviorAnomalyEntity loadOrThrow(UUID organizationId, UUID anomalyId) {
        return anomalyRepository.findByIdAndOrganizationId(anomalyId, organizationId)
                .orElseThrow(() -> new AnomalyNotFoundException(anomalyId));
    }

    private AnomalyBadgeView toBadge(List<BehaviorAnomalyEntity> open) {
        if (open.isEmpty()) {
            return AnomalyBadgeView.none();
        }
        double max = open.stream().mapToDouble(BehaviorAnomalyEntity::getScore).max().orElse(0.0);
        return new AnomalyBadgeView(open.size(), max);
    }

    private BehaviorAnomalyView toView(BehaviorAnomalyEntity entity) {
        var user = userQueryService.findById(entity.getUserId()).orElse(null);
        var datasource = datasourceLookupService.findRef(entity.getDatasourceId()).orElse(null);
        return new BehaviorAnomalyView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getUserId(),
                user != null ? user.displayName() : null,
                user != null ? user.email() : null,
                entity.getDatasourceId(),
                datasource != null ? datasource.name() : null,
                entity.getFeature(),
                entity.getScore(),
                entity.getObservedValue(),
                entity.getBaselineMean(),
                entity.getBaselineStddev(),
                parseDetail(entity.getDetail()),
                entity.getAiSummary(),
                entity.getStatus(),
                entity.getDetectedAt(),
                entity.getAcknowledgedBy(),
                entity.getAcknowledgedAt(),
                entity.getWindowStart(),
                entity.getWindowEnd());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (RuntimeException ex) {
            log.warn("Unparseable anomaly detail JSON: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        var sort = request.sort().isEmpty()
                ? Sort.by(Sort.Direction.DESC, "detectedAt")
                : Sort.by(request.sort().stream().map(DefaultBehaviorAnomalyService::toSpringOrder).toList());
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
