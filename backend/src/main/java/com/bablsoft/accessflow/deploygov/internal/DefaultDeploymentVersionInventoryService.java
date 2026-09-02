package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionInventoryService;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentVersionExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only version inventory and drift over the #741 tracking projection (#742). Drift is
 * computed at read time and nowhere else — no scheduled job, no notifications, no semver: version
 * comparison is plain string inequality.
 *
 * <p>The org-wide listing filters and slices <em>in memory</em>: the {@code drifted} filter is
 * relative to a per-pipeline latest that must be computed over the pipeline's <em>unfiltered</em>
 * row set, so it cannot be a SQL predicate — and the row cardinality is bounded at one per
 * admin-configured environment, so loading the org's rows is cheap. {@code deploymentsBehind} is
 * the only per-row cost (a grouped query per pipeline) and is resolved for the returned page
 * only.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentVersionInventoryService implements DeploymentVersionInventoryService {

    private final DeploymentEnvironmentVersionRepository versionRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentRequestRepository requestRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentEnvironmentVersionView> pipelineMatrix(UUID pipelineId,
                                                                 UUID organizationId, UUID callerId,
                                                                 Set<Permission> callerPermissions) {
        var pipeline = requireVisiblePipeline(pipelineId, organizationId, callerId,
                callerPermissions);
        var environments = environmentRepository
                .findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId);
        var rowsByEnvironment = versionRepository.findByPipelineId(pipelineId).stream()
                .collect(Collectors.toMap(DeploymentEnvironmentVersionEntity::getEnvironmentId,
                        Function.identity()));
        var latest = latestOf(rowsByEnvironment.values());
        var executions = requestRepository.findSuccessfulVersionExecutions(pipelineId,
                QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED);
        return environments.stream()
                .map(env -> toView(pipeline, env, rowsByEnvironment.get(env.getId()), latest,
                        executions))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentEnvironmentVersionView> list(
            DeploymentEnvironmentVersionListFilter filter, PageRequest pageRequest) {
        var rows = versionRepository.findAll(DeploymentEnvironmentVersionSpecifications
                .forList(filter.organizationId(), filter.pipelineId(), filter.tag()));
        var environmentsById = environmentRepository.findAllById(
                        rows.stream().map(DeploymentEnvironmentVersionEntity::getEnvironmentId).toList())
                .stream()
                .collect(Collectors.toMap(DeploymentEnvironmentEntity::getId, Function.identity()));
        var pipelinesById = pipelineRepository.findAllById(
                        rows.stream().map(DeploymentEnvironmentVersionEntity::getPipelineId)
                                .distinct().toList())
                .stream()
                .collect(Collectors.toMap(DeploymentPipelineEntity::getId, Function.identity()));
        // Latest per pipeline over the UNFILTERED row set — the tag filter above narrows which
        // rows are listed, never what they are compared against.
        var latestByPipeline = new HashMap<UUID, DeploymentEnvironmentVersionEntity>();
        pipelinesById.keySet().forEach(pid ->
                latestOf(versionRepository.findByPipelineId(pid))
                        .ifPresent(row -> latestByPipeline.put(pid, row)));

        var entries = new ArrayList<Entry>();
        for (var row : rows) {
            var environment = environmentsById.get(row.getEnvironmentId());
            var pipeline = pipelinesById.get(row.getPipelineId());
            if (environment == null || pipeline == null) {
                continue; // row raced a cascade delete — nothing to render
            }
            var latest = Optional.ofNullable(latestByPipeline.get(row.getPipelineId()));
            var drifted = !Objects.equals(row.getCurrentVersion(),
                    latest.map(DeploymentEnvironmentVersionEntity::getCurrentVersion).orElse(null));
            entries.add(new Entry(row, environment, pipeline, latest.orElse(null), drifted));
        }

        var filtered = entries.stream()
                .filter(e -> filter.environment() == null || filter.environment().isBlank()
                        || e.environment().getName().equalsIgnoreCase(filter.environment().trim()))
                .filter(e -> filter.drifted() == null || e.drifted() == filter.drifted())
                .sorted(Comparator
                        .comparing((Entry e) -> e.pipeline().getName(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(e -> e.pipeline().getId())
                        .thenComparingInt(e -> e.environment().getSortOrder())
                        .thenComparing(e -> e.environment().getName(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();

        var from = Math.min((long) pageRequest.page() * pageRequest.size(), filtered.size());
        var to = Math.min(from + pageRequest.size(), filtered.size());
        var pageEntries = filtered.subList((int) from, (int) to);

        // The grouped execution query is the only per-row cost — resolve it for the page only,
        // once per distinct pipeline on the page, and only for pipelines that need the number.
        var executionsByPipeline = new HashMap<UUID, List<DeploymentVersionExecution>>();
        var content = pageEntries.stream().map(e -> {
            var executions = e.drifted() && e.row().getDeployedAt() != null
                    ? executionsByPipeline.computeIfAbsent(e.row().getPipelineId(),
                            pid -> requestRepository.findSuccessfulVersionExecutions(pid,
                                    QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED))
                    : List.<DeploymentVersionExecution>of();
            return toView(e.pipeline(), e.environment(), e.row(), Optional.ofNullable(e.latest()),
                    executions);
        }).toList();

        var totalPages = pageRequest.size() <= 0 ? 0
                : (int) Math.ceil((double) filtered.size() / pageRequest.size());
        return new PageResponse<>(content, pageRequest.page(), pageRequest.size(), filtered.size(),
                totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentVersionHistoryEntryView> history(UUID pipelineId,
                                                                   UUID environmentId,
                                                                   QueryStatus status,
                                                                   UUID organizationId,
                                                                   UUID callerId,
                                                                   Set<Permission> callerPermissions,
                                                                   PageRequest pageRequest) {
        requireVisiblePipeline(pipelineId, organizationId, callerId, callerPermissions);
        environmentRepository.findById(environmentId)
                .filter(env -> env.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(environmentId));
        var pageable = toPageable(pageRequest);
        var page = status == null
                ? requestRepository.findByPipelineIdAndEnvironmentIdOrderByCreatedAtDesc(
                        pipelineId, environmentId, pageable)
                : requestRepository.findByPipelineIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
                        pipelineId, environmentId, status, pageable);
        return toPage(page);
    }

    /**
     * Inventory visibility — 404 on failure, never 403, exactly like the gate: an
     * under-permissioned read must look like an unknown pipeline, so the endpoints are not an
     * existence oracle. {@code QUERY_ADMIN} is accepted alongside the two functional permissions,
     * matching every other deploygov view-all predicate.
     */
    private DeploymentPipelineEntity requireVisiblePipeline(UUID pipelineId, UUID organizationId,
                                                            UUID callerId,
                                                            Set<Permission> callerPermissions) {
        var pipeline = pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(pipelineId));
        if (canViewAll(callerPermissions) || canTrigger(pipelineId, callerId)) {
            return pipeline;
        }
        throw new DeploymentPipelineNotFoundException(pipelineId);
    }

    private static boolean canViewAll(Set<Permission> callerPermissions) {
        return callerPermissions != null
                && (callerPermissions.contains(Permission.DEPLOYMENT_PIPELINE_MANAGE)
                        || callerPermissions.contains(Permission.DEPLOYMENT_REVIEW)
                        || callerPermissions.contains(Permission.QUERY_ADMIN));
    }

    private boolean canTrigger(UUID pipelineId, UUID callerId) {
        return permissionResolver.resolve(pipelineId, callerId)
                .map(EffectiveDeploymentPermission::canTrigger)
                .orElse(false);
    }

    /**
     * The pipeline's newest successful deploy: max {@code deployedAt} among rows whose
     * {@code lastOutcome} is null or {@code SUCCEEDED} — a reverted row's outcome refers to the
     * reverted request, so it never nominates the latest.
     */
    private static Optional<DeploymentEnvironmentVersionEntity> latestOf(
            Iterable<DeploymentEnvironmentVersionEntity> rows) {
        DeploymentEnvironmentVersionEntity best = null;
        for (var row : rows) {
            if (row.getDeployedAt() == null || (row.getLastOutcome() != null
                    && row.getLastOutcome() != DeploymentOutcome.SUCCEEDED)) {
                continue;
            }
            if (best == null || row.getDeployedAt().isAfter(best.getDeployedAt())) {
                best = row;
            }
        }
        return Optional.ofNullable(best);
    }

    private DeploymentEnvironmentVersionView toView(DeploymentPipelineEntity pipeline,
                                                    DeploymentEnvironmentEntity environment,
                                                    DeploymentEnvironmentVersionEntity row,
                                                    Optional<DeploymentEnvironmentVersionEntity> latest,
                                                    List<DeploymentVersionExecution> executions) {
        var drift = driftOf(row, latest, executions);
        return new DeploymentEnvironmentVersionView(pipeline.getId(), pipeline.getName(),
                environment.getId(), environment.getName(),
                Arrays.asList(environment.getTags()), environment.getSortOrder(),
                row == null ? null : row.getCurrentVersion(),
                row == null ? null : row.getCurrentRequestId(),
                row == null ? null : row.getDeployedAt(),
                row == null ? null : row.getPreviousVersion(),
                row == null ? null : row.getLastOutcome(),
                drift);
    }

    private DeploymentVersionDriftView driftOf(DeploymentEnvironmentVersionEntity row,
                                               Optional<DeploymentEnvironmentVersionEntity> latest,
                                               List<DeploymentVersionExecution> executions) {
        var latestVersion = latest
                .map(DeploymentEnvironmentVersionEntity::getCurrentVersion).orElse(null);
        var latestDeployedAt = latest
                .map(DeploymentEnvironmentVersionEntity::getDeployedAt).orElse(null);
        var currentVersion = row == null ? null : row.getCurrentVersion();
        var deployedAt = row == null ? null : row.getDeployedAt();
        var drifted = !Objects.equals(currentVersion, latestVersion);
        if (!drifted) {
            // An environment on the latest version is never "behind", however early it got it.
            return new DeploymentVersionDriftView(latestVersion, latestDeployedAt, false, 0L, 0L);
        }
        Long daysBehind = deployedAt == null || latestDeployedAt == null ? null
                : Math.max(0L, Duration.between(deployedAt, latestDeployedAt).toDays());
        Long deploymentsBehind = deployedAt == null ? null
                : executions.stream()
                        .filter(e -> e.lastExecutedAt() != null
                                && e.lastExecutedAt().isAfter(deployedAt)
                                && !Objects.equals(e.version(), currentVersion))
                        .count();
        return new DeploymentVersionDriftView(latestVersion, latestDeployedAt, true, daysBehind,
                deploymentsBehind);
    }

    private PageResponse<DeploymentVersionHistoryEntryView> toPage(
            Page<DeploymentRequestEntity> page) {
        var content = page.getContent().stream().map(this::toHistoryView).toList();
        return new PageResponse<>(content, page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    private DeploymentVersionHistoryEntryView toHistoryView(DeploymentRequestEntity entity) {
        return new DeploymentVersionHistoryEntryView(entity.getId(), entity.getVersion(),
                entity.getStatus(), entity.getOutcome(), entity.getOutcomeReportedAt(),
                entity.getSubmittedBy(), entity.getSubmissionReason(), entity.getCommitSha(),
                entity.getRunUrl(), entity.getCreatedAt(), entity.getExecutedAt());
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(),
                pageRequest.size());
    }

    /** In-memory join of one tracker row with its environment, pipeline, and drift verdict. */
    private record Entry(DeploymentEnvironmentVersionEntity row,
                         DeploymentEnvironmentEntity environment,
                         DeploymentPipelineEntity pipeline,
                         DeploymentEnvironmentVersionEntity latest,
                         boolean drifted) {
    }
}
