package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderBlocker;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungState;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineEnvironmentView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Previews the promotion gate per environment (#883) so the UI can say why a rung is blocked
 * instead of rendering it disabled. It runs {@link DefaultSchemaChangePromotionService}'s checks in
 * the gate's order — minus the promoter-specific {@code can_ddl} and the plan-specific review
 * guard — plus one advisory the gate does not enforce ({@code PARTIALLY_APPLIED}). The real gate
 * still runs on every promotion.
 */
@Service
@RequiredArgsConstructor
class DefaultSchemaChangeLadderService implements SchemaChangeLadderService {

    private static final int PIPELINE_PAGE_SIZE = 100;

    private final SchemaChangeSetRepository changeSetRepository;
    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final DeploymentPipelineAdminService pipelineAdminService;
    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DeploymentFreezeLookupService freezeLookupService;
    private final DatasourceAdminService datasourceAdminService;

    @Override
    @Transactional(readOnly = true)
    public List<SchemaChangePipelineView> listPipelines(UUID organizationId) {
        var result = new ArrayList<SchemaChangePipelineView>();
        var page = 0;
        while (true) {
            var slice = pipelineAdminService.list(organizationId, PageRequest.of(page, PIPELINE_PAGE_SIZE));
            for (var pipeline : slice.content()) {
                var environments = environmentLookupService.listByPipeline(pipeline.id()).stream()
                        .map(e -> new SchemaChangePipelineEnvironmentView(e.id(), e.name(), e.sortOrder(),
                                e.datasourceId()))
                        .toList();
                result.add(new SchemaChangePipelineView(pipeline.id(), pipeline.name(), pipeline.active(),
                        environments));
            }
            page++;
            if (page >= slice.totalPages()) {
                return result;
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SchemaChangeLadderView ladder(UUID organizationId, UUID changeSetId) {
        var changeSet = changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId)
                .orElseThrow(() -> new SchemaChangeSetNotFoundException(changeSetId));
        var environments = environmentLookupService.listByPipeline(changeSet.getPipelineId());
        var promotions = promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(changeSetId);

        var latest = new HashMap<UUID, SchemaChangeSetPromotionEntity>();
        var open = new HashSet<UUID>();
        var applied = new HashSet<UUID>();
        for (var p : promotions) {
            latest.putIfAbsent(p.getEnvironmentId(), p);
            if (!p.getStatus().isTerminal()) {
                open.add(p.getEnvironmentId());
            } else if (p.getStatus() == SchemaChangePromotionStatus.APPLIED) {
                applied.add(p.getEnvironmentId());
            }
        }
        var ladderValid = distinctSortOrders(environments);
        var names = new HashMap<UUID, String>();
        environments.forEach(e -> names.put(e.id(), e.name()));

        // The binding is a bare id: a deleted datasource stays bound, and the gate refuses it (check 6).
        var existence = new HashMap<UUID, Boolean>();
        Predicate<UUID> datasourceExists = id -> existence.computeIfAbsent(id,
                key -> datasourceExists(organizationId, key));
        var rungs = environments.stream()
                .map(e -> rung(organizationId, changeSet, e, environments, latest, open, applied, ladderValid, names,
                        datasourceExists))
                .toList();
        return new SchemaChangeLadderView(changeSetId, changeSet.getPipelineId(), rungs);
    }

    @SuppressWarnings("java:S107")
    private SchemaChangeLadderRungView rung(UUID organizationId, SchemaChangeSetEntity changeSet,
                                            DeploymentEnvironmentView environment,
                                            List<DeploymentEnvironmentView> ladder,
                                            Map<UUID, SchemaChangeSetPromotionEntity> latest, Set<UUID> open,
                                            Set<UUID> applied, boolean ladderValid, Map<UUID, String> names,
                                            Predicate<UUID> datasourceExists) {
        var newest = latest.get(environment.id());
        var promotionView = newest == null ? null : withoutSnapshot(newest, names.get(environment.id()));
        if (open.contains(environment.id())) {
            return state(environment, promotionView, SchemaChangeLadderRungState.IN_PROGRESS);
        }
        if (applied.contains(environment.id())) {
            return state(environment, promotionView, SchemaChangeLadderRungState.APPLIED);
        }
        if (changeSet.getStatus() == SchemaChangeSetStatus.ARCHIVED) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.SET_ARCHIVED);
        }
        if (changeSet.getStatementsChecksum() == null) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.SET_EMPTY);
        }
        if (environment.datasourceId() == null) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.NO_DATASOURCE);
        }
        if (!datasourceExists.test(environment.datasourceId())) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.DATASOURCE_MISSING);
        }
        if (newest != null && newest.getStatus() == SchemaChangePromotionStatus.PARTIALLY_APPLIED) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.PARTIALLY_APPLIED);
        }
        if (!ladderValid) {
            return blocked(environment, promotionView, SchemaChangeLadderBlocker.LADDER_INVALID);
        }
        for (var lower : ladder) {
            if (lower.sortOrder() < environment.sortOrder() && lower.datasourceId() != null
                    && !applied.contains(lower.id())) {
                return new SchemaChangeLadderRungView(environment.id(), environment.name(), environment.sortOrder(),
                        environment.datasourceId(), promotionView, SchemaChangeLadderRungState.BLOCKED,
                        SchemaChangeLadderBlocker.LOWER_ENVIRONMENT_NOT_APPLIED, lower.id(), lower.name(),
                        null, null, null);
            }
        }
        var freeze = freezeLookupService.evaluate(organizationId, changeSet.getPipelineId(), environment.id());
        if (freeze.isPresent()) {
            var window = freeze.get();
            return new SchemaChangeLadderRungView(environment.id(), environment.name(), environment.sortOrder(),
                    environment.datasourceId(), promotionView, SchemaChangeLadderRungState.BLOCKED,
                    SchemaChangeLadderBlocker.FREEZE_ACTIVE, null, null, window.windowId(), window.behavior(),
                    window.reason());
        }
        return state(environment, promotionView, SchemaChangeLadderRungState.PROMOTABLE);
    }

    private boolean datasourceExists(UUID organizationId, UUID datasourceId) {
        try {
            datasourceAdminService.getForAdmin(datasourceId, organizationId);
            return true;
        } catch (DatasourceNotFoundException ex) {
            return false;
        }
    }

    private static boolean distinctSortOrders(List<DeploymentEnvironmentView> environments) {
        var orders = new HashSet<Integer>();
        return environments.stream().allMatch(e -> orders.add(e.sortOrder()));
    }

    private static SchemaChangeLadderRungView state(DeploymentEnvironmentView environment,
                                                    SchemaChangePromotionView promotion,
                                                    SchemaChangeLadderRungState state) {
        return new SchemaChangeLadderRungView(environment.id(), environment.name(), environment.sortOrder(),
                environment.datasourceId(), promotion, state, null, null, null, null, null, null);
    }

    private static SchemaChangeLadderRungView blocked(DeploymentEnvironmentView environment,
                                                      SchemaChangePromotionView promotion,
                                                      SchemaChangeLadderBlocker blocker) {
        return new SchemaChangeLadderRungView(environment.id(), environment.name(), environment.sortOrder(),
                environment.datasourceId(), promotion, SchemaChangeLadderRungState.BLOCKED, blocker,
                null, null, null, null, null);
    }

    private static SchemaChangePromotionView withoutSnapshot(SchemaChangeSetPromotionEntity p, String environmentName) {
        return new SchemaChangePromotionView(p.getId(), p.getOrganizationId(), p.getChangeSet().getId(),
                p.getEnvironmentId(), environmentName, p.getDatasourceId(), p.getRequestGroupId(), p.getStatus(),
                p.getStatementsChecksum(), p.getPromotedBy(), p.getSubmittedAt(), p.getAppliedAt(),
                p.getErrorMessage(), null, p.getSnapshotTakenAt());
    }
}
