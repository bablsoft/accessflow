package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.events.GrantStaleEvent;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageWatermarkEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageSummaryRepository;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageWatermarkRepository;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.audit.api.GrantUsageAuditAggregationService;
import com.bablsoft.accessflow.audit.api.GrantUsageAuditEvent;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Folds audit-derived usage into the per-grant summaries that back least-privilege intelligence
 * (#625). One organization per call; the job supplies the loop and the error isolation.
 *
 * <p>Each call runs four phases in order:
 *
 * <ol>
 *   <li><strong>Reconcile</strong> the summaries against the organization's live standing grants —
 *       upsert one row per grant, delete rows whose grant is gone. A newly-created row is backfilled
 *       over {@code [observedSince, cursor)} straight away, because the organization's fold cursor
 *       has usually already passed that window and the row would otherwise read as never-used
 *       forever.</li>
 *   <li><strong>Fold</strong> new audit events from the cursor forward. On a full page the cursor
 *       advances to the last event rather than the window end, so the tail is picked up next tick
 *       instead of being skipped.</li>
 *   <li><strong>Recompute</strong> every recommendation, so a threshold change or the mere passage
 *       of time is reflected without waiting for new activity.</li>
 *   <li><strong>Nudge</strong> on rows that have gone stale and are outside the cooldown.</li>
 * </ol>
 *
 * <p>The whole call is one transaction, which is also what makes the nudge safe: the notifications
 * module consumes {@link GrantStaleEvent} through an {@code @ApplicationModuleListener}
 * (AFTER_COMMIT), and an event published outside a transaction would be dropped silently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DefaultGrantUsageAggregationService implements GrantUsageAggregationService {

    private static final int ORGANIZATION_PAGE_SIZE = 100;

    private final GrantUsageSummaryRepository summaryRepository;
    private final GrantUsageWatermarkRepository watermarkRepository;
    private final GrantUsageAuditAggregationService auditAggregationService;
    private final OrganizationAdminService organizationAdminService;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final ApiConnectorLookupService connectorLookupService;
    private final ApiConnectorAdminService connectorAdminService;
    private final GrantUsageViewMapper viewMapper;
    private final AccessProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findOrganizationIds() {
        var ids = new ArrayList<UUID>();
        int page = 0;
        int totalPages;
        do {
            // Sorted explicitly: each page is its own read, so an unsorted LIMIT/OFFSET lets
            // Postgres reorder between pages and silently skip an organization.
            var organizations = organizationAdminService.list(
                    PageRequest.of(page, ORGANIZATION_PAGE_SIZE, SortOrder.asc("id")));
            totalPages = organizations.totalPages();
            organizations.content().stream()
                    .filter(organization -> !organization.disabled())
                    .forEach(organization -> ids.add(organization.id()));
            page++;
        } while (page < totalPages);
        return ids;
    }

    @Override
    @Transactional
    public int aggregateOrganization(UUID organizationId, Instant now) {
        var usage = properties.usage();
        var cursor = loadCursor(organizationId, now, usage.backfillWindow());

        var reconciled = reconcile(organizationId, now, cursor, usage);
        var summaries = reconciled.summaries();
        foldUsage(organizationId, cursor, now, summaries, usage);
        recomputeExercisedCounts(summaries, reconciled.grantedTargets());
        recomputeRecommendations(now, summaries.values(), usage);
        nudgeStaleGrants(now, summaries.values(), usage);

        summaryRepository.saveAll(summaries.values());
        return summaries.size();
    }

    // ---------------------------------------------------------------- phase 1: reconcile

    /**
     * Upserts a summary per live grant and drops the rest, returning the surviving rows keyed by
     * grant. Deletion is what keeps the report honest: a revoked grant must disappear from
     * "over-provisioned access", not linger as a permanently idle row.
     */
    private Reconciled reconcile(UUID organizationId, Instant now, Instant cursor,
                                 AccessProperties.Usage usage) {
        var existing = new HashMap<GrantKey, GrantUsageSummaryEntity>();
        for (var row : summaryRepository.findByOrganizationId(organizationId)) {
            existing.put(GrantKey.of(row), row);
        }

        var live = new HashMap<GrantKey, GrantUsageSummaryEntity>();
        var grantedTargets = new HashMap<GrantKey, Set<String>>();
        var created = new ArrayList<GrantUsageSummaryEntity>();
        for (var grant : liveGrants(organizationId)) {
            var key = grant.key();
            var row = existing.remove(key);
            if (row == null) {
                row = newSummary(organizationId, grant, now, usage.backfillWindow());
                created.add(row);
            } else {
                applyGrantShape(row, grant);
            }
            live.put(key, row);
            grantedTargets.put(key, grant.grantedTargets());
        }

        if (!existing.isEmpty()) {
            summaryRepository.deleteAll(existing.values());
            log.debug("Removed {} grant usage summaries for revoked grants in org {}",
                    existing.size(), organizationId);
        }
        backfill(organizationId, created, cursor, usage);
        return new Reconciled(live, grantedTargets);
    }

    /** Every standing grant in the organization, across both resource kinds. */
    private List<LiveGrant> liveGrants(UUID organizationId) {
        var grants = new ArrayList<LiveGrant>();
        for (var datasource : datasourceLookupService.findActiveRefsByOrganization(organizationId)) {
            for (var permission : datasourceAdminService.listPermissions(datasource.id(),
                    organizationId)) {
                grants.add(new LiveGrant(GrantResourceKind.DATASOURCE, datasource.id(),
                        datasource.name(), permission.id(), permission.userId(),
                        permission.userEmail(), permission.userDisplayName(),
                        permission.createdAt(), permission.expiresAt(),
                        GrantTargetNormalizer.normalizeAll(permission.allowedTables())));
            }
        }
        for (var connector : connectorLookupService.findActiveRefsByOrganization(organizationId)) {
            for (var permission : connectorAdminService.listPermissions(connector.id(),
                    organizationId)) {
                grants.add(new LiveGrant(GrantResourceKind.API_CONNECTOR, connector.id(),
                        connector.name(), permission.id(), permission.userId(),
                        permission.userEmail(), permission.userDisplayName(),
                        permission.createdAt(), permission.expiresAt(),
                        GrantTargetNormalizer.normalizeAll(permission.allowedOperations())));
            }
        }
        return grants;
    }

    private GrantUsageSummaryEntity newSummary(UUID organizationId, LiveGrant grant, Instant now,
                                               Duration backfillWindow) {
        var row = new GrantUsageSummaryEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(organizationId);
        row.setResourceKind(grant.resourceKind());
        row.setResourceId(grant.resourceId());
        row.setUserId(grant.userId());
        // Observation cannot start before the grant existed, nor reach further back than the
        // configured backfill window — whichever is later is the honest denominator for frequency.
        var earliest = now.minus(backfillWindow);
        row.setObservedSince(grant.grantedAt() != null && grant.grantedAt().isAfter(earliest)
                ? grant.grantedAt() : earliest);
        applyGrantShape(row, grant);
        return row;
    }

    /** Refreshes the denormalised grant shape; usage counters are untouched. */
    private void applyGrantShape(GrantUsageSummaryEntity row, LiveGrant grant) {
        row.setResourceName(grant.resourceName());
        row.setPermissionId(grant.permissionId());
        row.setUserEmail(grant.userEmail());
        row.setUserDisplayName(grant.userDisplayName());
        row.setGrantedAt(grant.grantedAt() == null ? row.getObservedSince() : grant.grantedAt());
        row.setExpiresAt(grant.expiresAt());
        // Empty allow-list means unrestricted, which is null here and NOT zero — an unrestricted
        // grant has no scope to under-use, so it can never be reported over-scoped.
        row.setGrantedTargetCount(grant.grantedTargets().isEmpty()
                ? null : grant.grantedTargets().size());
    }

    /**
     * Replays audit history for grants summarised for the first time. Without this a grant created
     * after the organization's cursor moved forward would never see its own past.
     */
    private void backfill(UUID organizationId, List<GrantUsageSummaryEntity> created, Instant cursor,
                          AccessProperties.Usage usage) {
        if (created.isEmpty()) {
            return;
        }
        var earliest = created.stream()
                .map(GrantUsageSummaryEntity::getObservedSince)
                .min(Instant::compareTo)
                .orElse(cursor);
        if (!earliest.isBefore(cursor)) {
            return;
        }
        var byKey = new HashMap<GrantKey, GrantUsageSummaryEntity>();
        created.forEach(row -> byKey.put(GrantKey.of(row), row));
        var events = auditAggregationService.findUsageEvents(organizationId, earliest, cursor,
                usage.maxRowsPerTick());
        applyEvents(events, byKey, usage);
        log.debug("Backfilled {} new grant usage summaries in org {} from {} audit events",
                created.size(), organizationId, events.size());
    }

    // ---------------------------------------------------------------- phase 2: fold

    private void foldUsage(UUID organizationId, Instant cursor, Instant now,
                           Map<GrantKey, GrantUsageSummaryEntity> summaries,
                           AccessProperties.Usage usage) {
        if (!cursor.isBefore(now)) {
            return;
        }
        var events = auditAggregationService.findUsageEvents(organizationId, cursor, now,
                usage.maxRowsPerTick());
        applyEvents(events, summaries, usage);

        // A full page means the window was not drained. Resuming from the window end would skip
        // everything after the last event we saw, so resume from that event instead. The +1ms keeps
        // the half-open read from re-serving the same final row forever.
        var next = events.size() >= usage.maxRowsPerTick() && !events.isEmpty()
                ? events.get(events.size() - 1).occurredAt().plusMillis(1)
                : now;
        if (events.size() >= usage.maxRowsPerTick()) {
            log.info("Grant usage fold for org {} hit the {}-row cap; resuming from {}",
                    organizationId, usage.maxRowsPerTick(), next);
        }
        saveCursor(organizationId, next);
    }

    private void applyEvents(List<GrantUsageAuditEvent> events,
                             Map<GrantKey, GrantUsageSummaryEntity> summaries,
                             AccessProperties.Usage usage) {
        for (var event : events) {
            var row = summaries.get(new GrantKey(event.resourceKind(), event.resourceId(),
                    event.userId()));
            if (row == null) {
                // No live grant for this event — revoked since, or a QUERY_ADMIN holder who needs
                // no grant at all. Either way there is nothing to attribute the usage to.
                continue;
            }
            apply(row, event, usage);
        }
    }

    private void apply(GrantUsageSummaryEntity row, GrantUsageAuditEvent event,
                       AccessProperties.Usage usage) {
        row.setUsageCount(row.getUsageCount() + 1);
        if (row.getFirstUsedAt() == null || event.occurredAt().isBefore(row.getFirstUsedAt())) {
            row.setFirstUsedAt(event.occurredAt());
        }
        if (row.getLastUsedAt() == null || event.occurredAt().isAfter(row.getLastUsedAt())) {
            row.setLastUsedAt(event.occurredAt());
        }
        if (event.targets().isEmpty()) {
            return;
        }
        var targets = new LinkedHashSet<>(viewMapper.readUsedTargets(row));
        int before = targets.size();
        for (var target : GrantTargetNormalizer.normalizeAll(event.targets())) {
            if (targets.size() >= usage.maxTrackedTargets()) {
                if (!targets.contains(target)) {
                    log.info("Grant usage summary {} hit the {}-target cap; further distinct "
                                    + "targets are not tracked", row.getId(),
                            usage.maxTrackedTargets());
                }
                break;
            }
            targets.add(target);
        }
        if (targets.size() != before) {
            row.setUsedTargets(viewMapper.writeUsedTargets(List.copyOf(targets)));
        }
    }

    // ---------------------------------------------------------------- phase 3 + 4

    /**
     * Recomputes how much of each grant's scope has been exercised, using the live allow-list rather
     * than the stored count. For a scope-limited grant this counts <em>granted</em> entries actually
     * touched — so a query against a table outside the allow-list (possible for a {@code QUERY_ADMIN}
     * holder, whose submissions bypass the per-grant check) can never inflate the figure past the
     * grant's own scope, and a shrunken allow-list is reflected immediately. For an unrestricted
     * grant there is no allow-list to intersect, so every distinct target counts.
     */
    private void recomputeExercisedCounts(Map<GrantKey, GrantUsageSummaryEntity> summaries,
                                          Map<GrantKey, Set<String>> grantedTargets) {
        summaries.forEach((key, row) -> {
            var used = Set.copyOf(viewMapper.readUsedTargets(row));
            var granted = grantedTargets.getOrDefault(key, Set.of());
            row.setUsedTargetCount(granted.isEmpty()
                    ? used.size()
                    : GrantTargetNormalizer.countGrantedExercised(granted, used));
        });
    }

    private void recomputeRecommendations(Instant now,
                                          Iterable<GrantUsageSummaryEntity> summaries,
                                          AccessProperties.Usage usage) {
        var recommender = new GrantUsageRecommender(usage.minObservationWindow(),
                usage.stalenessThreshold(), usage.overScopedThreshold());
        for (var row : summaries) {
            row.setRecommendation(recommender.recommend(now, row.getObservedSince(),
                    row.getLastUsedAt(), row.getUsageCount(), row.getGrantedTargetCount(),
                    row.getUsedTargetCount()));
        }
    }

    private void nudgeStaleGrants(Instant now, Iterable<GrantUsageSummaryEntity> summaries,
                                  AccessProperties.Usage usage) {
        if (!Boolean.TRUE.equals(usage.nudgeEnabled())) {
            return;
        }
        for (var row : summaries) {
            if (!isNudgeable(row.getRecommendation()) || withinCooldown(row, now, usage)) {
                continue;
            }
            row.setNudgedAt(now);
            eventPublisher.publishEvent(new GrantStaleEvent(
                    row.getOrganizationId(), row.getId(), row.getUserId(), row.getUserEmail(),
                    row.getResourceKind(), row.getResourceId(), row.getResourceName(),
                    row.getLastUsedAt() == null
                            ? null : Duration.between(row.getLastUsedAt(), now).toDays(),
                    row.getRecommendation()));
        }
    }

    private static boolean isNudgeable(GrantUsageRecommendation recommendation) {
        return recommendation == GrantUsageRecommendation.NEVER_USED
                || recommendation == GrantUsageRecommendation.STALE;
    }

    private static boolean withinCooldown(GrantUsageSummaryEntity row, Instant now,
                                          AccessProperties.Usage usage) {
        return row.getNudgedAt() != null
                && Duration.between(row.getNudgedAt(), now).compareTo(usage.nudgeCooldown()) < 0;
    }

    // ---------------------------------------------------------------- cursor

    private Instant loadCursor(UUID organizationId, Instant now, Duration backfillWindow) {
        return watermarkRepository.findById(organizationId)
                .map(GrantUsageWatermarkEntity::getAggregatedThrough)
                .orElseGet(() -> now.minus(backfillWindow));
    }

    private void saveCursor(UUID organizationId, Instant aggregatedThrough) {
        var watermark = watermarkRepository.findById(organizationId)
                .orElseGet(() -> {
                    var fresh = new GrantUsageWatermarkEntity();
                    fresh.setOrganizationId(organizationId);
                    return fresh;
                });
        watermark.setAggregatedThrough(aggregatedThrough);
        watermarkRepository.save(watermark);
    }

    // ---------------------------------------------------------------- value types

    /**
     * Reconciliation output: the surviving summaries and each grant's live allow-list, which phase 3
     * needs to intersect against the exercised targets.
     */
    private record Reconciled(Map<GrantKey, GrantUsageSummaryEntity> summaries,
                              Map<GrantKey, Set<String>> grantedTargets) {
    }

    /** Identity of a standing grant: the resource it covers and who holds it. */
    private record GrantKey(GrantResourceKind resourceKind, UUID resourceId, UUID userId) {

        static GrantKey of(GrantUsageSummaryEntity row) {
            return new GrantKey(row.getResourceKind(), row.getResourceId(), row.getUserId());
        }
    }

    /** A live standing grant flattened across both resource kinds. */
    private record LiveGrant(GrantResourceKind resourceKind, UUID resourceId, String resourceName,
                             UUID permissionId, UUID userId, String userEmail,
                             String userDisplayName, Instant grantedAt, Instant expiresAt,
                             Set<String> grantedTargets) {

        GrantKey key() {
            return new GrantKey(resourceKind, resourceId, userId);
        }
    }
}
