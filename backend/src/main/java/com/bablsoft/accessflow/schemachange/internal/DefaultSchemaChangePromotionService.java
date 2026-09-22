package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.schemachange.api.PromoteSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNoDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionDdlForbiddenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotCancellableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionReviewUnenforceableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetEmptyException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetTargetDatasourceMissingException;
import com.bablsoft.accessflow.schemachange.events.SchemaChangePromotionStatusChangedEvent;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The promotion path (#880, epic #870): promote a change set to one deployment environment as an
 * ordered request group. Every check in {@link #promote} fails closed, in a fixed order — change
 * set, archive, statements, environment, datasource, {@code can_ddl}, ladder, freeze, review
 * guard, open-promotion conflict — before anything is written. Execution is deferred to the
 * requestgroups {@code ScheduledGroupRunJob} by submitting the group with {@code scheduledFor}
 * = now: approval alone never executes a group, and the job is the durable trigger.
 */
@Service
@RequiredArgsConstructor
public class DefaultSchemaChangePromotionService implements SchemaChangePromotionService {

    /** V178's partial unique index over the non-terminal promotion states — the one violation translated to 409. */
    static final String OPEN_CONSTRAINT = "uq_schema_change_set_promotions_open";

    static final Set<SchemaChangePromotionStatus> OPEN_STATUSES = EnumSet.of(
            SchemaChangePromotionStatus.PENDING, SchemaChangePromotionStatus.IN_REVIEW,
            SchemaChangePromotionStatus.APPROVED);

    private static final int GROUP_NAME_MAX = 255;

    private final SchemaChangeSetRepository changeSetRepository;
    private final SchemaChangeSetStatementRepository statementRepository;
    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final DeploymentPipelineLookupService pipelineLookupService;
    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DeploymentFreezeLookupService freezeLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final RequestGroupService requestGroupService;
    private final SchemaChangeAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public SchemaChangePromotionView promote(UUID organizationId, UUID actorId, UUID changeSetId,
                                             PromoteSchemaChangeSetCommand command) {
        var changeSet = requireChangeSet(organizationId, changeSetId);
        if (changeSet.getStatus() == SchemaChangeSetStatus.ARCHIVED) {
            throw new SchemaChangeSetArchivedException(changeSetId);
        }
        var statements = statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(changeSetId);
        if (statements.isEmpty()) {
            throw new SchemaChangeSetEmptyException(changeSetId);
        }
        var environment = pipelineLookupService.findEnvironment(changeSet.getPipelineId(), command.environmentId())
                .orElseThrow(() -> new SchemaChangeEnvironmentNotFoundException(command.environmentId()));
        var datasourceId = environment.datasourceId();
        if (datasourceId == null) {
            throw new SchemaChangeEnvironmentNoDatasourceException(environment.id());
        }
        requireDatasource(organizationId, changeSet.getPipelineId(), datasourceId);
        requireDdl(actorId, datasourceId);
        requireLadder(changeSet, environment);
        requireNotFrozen(organizationId, changeSet.getPipelineId(), environment);
        requireEnforceableReview(environment, datasourceId);
        if (promotionRepository.existsByChangeSet_IdAndEnvironmentIdAndStatusIn(
                changeSetId, environment.id(), OPEN_STATUSES)) {
            throw new SchemaChangePromotionConflictException(changeSetId, environment.id());
        }

        var sqlTexts = statements.stream().map(SchemaChangeSetStatementEntity::getSqlText).toList();
        var checksum = SchemaChangeChecksum.of(sqlTexts);
        if (!checksum.equals(changeSet.getStatementsChecksum())) {
            throw new IllegalStateException("Stored checksum of change set " + changeSetId
                    + " does not match its statements");
        }

        var promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(UUID.randomUUID());
        promotion.setOrganizationId(organizationId);
        promotion.setChangeSet(changeSet);
        promotion.setEnvironmentId(environment.id());
        promotion.setDatasourceId(datasourceId);
        promotion.setStatus(SchemaChangePromotionStatus.PENDING);
        promotion.setStatementsChecksum(checksum);
        promotion.setPromotedBy(actorId);
        promotion.setSubmittedAt(clock.instant());
        // Flushed before the group exists so a raced duplicate never leaves an orphan request group.
        var saved = savePromotion(promotion);

        // admin=true is deliberate: schemachange has already enforced can_ddl above, for every
        // statement and every promoter. requestgroups' own per-member check would exempt QUERY_ADMIN
        // holders (the bypass this module must not inherit) and demand can_write for the
        // OTHER-classified statements (GRANT, COMMENT ON) the authoring gate admits on purpose.
        var draft = requestGroupService.createDraft(new CreateRequestGroupCommand(organizationId, actorId, true,
                groupName(changeSet.getName(), environment.name()),
                "Schema change set " + changeSet.getName() + " promoted to " + environment.name()
                        + " (promotion " + saved.getId() + ")",
                false, toItems(datasourceId, statements)));
        requestGroupService.submit(new SubmitRequestGroupCommand(draft.id(), organizationId, actorId, true, false,
                clock.instant(), command.submittedIp(), command.submittedUserAgent()));
        saved.setRequestGroupId(draft.id());
        saved = promotionRepository.saveAndFlush(saved);

        if (changeSet.getStatus() == SchemaChangeSetStatus.DRAFT) {
            changeSet.setStatus(SchemaChangeSetStatus.ACTIVE);
            changeSetRepository.saveAndFlush(changeSet);
        }

        var metadata = new HashMap<String, Object>();
        metadata.put("change_set_id", changeSetId.toString());
        metadata.put("environment_id", environment.id().toString());
        metadata.put("environment_name", environment.name());
        metadata.put("datasource_id", datasourceId.toString());
        metadata.put("request_group_id", draft.id().toString());
        metadata.put("statement_count", statements.size());
        metadata.put("statements_checksum", checksum);
        auditWriter.record(AuditAction.SCHEMA_CHANGE_PROMOTION_SUBMITTED, saved.getId(), organizationId, actorId,
                metadata, command.submittedIp(), command.submittedUserAgent());
        eventPublisher.publishEvent(new SchemaChangePromotionStatusChangedEvent(saved.getId(), changeSetId,
                environment.id(), organizationId, actorId, null, SchemaChangePromotionStatus.PENDING));
        return toView(saved, environment.name());
    }

    @Override
    @Transactional(readOnly = true)
    public SchemaChangePromotionView get(UUID organizationId, UUID promotionId) {
        return toView(requirePromotion(organizationId, promotionId));
    }

    /**
     * The listing deliberately omits {@code schemaSnapshot} — a whole introspection per row, on an
     * unpaginated read the UI polls. {@link #get} serves it.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SchemaChangePromotionView> listForChangeSet(UUID organizationId, UUID changeSetId) {
        var changeSet = requireChangeSet(organizationId, changeSetId);
        var names = environmentLookupService.listByPipeline(changeSet.getPipelineId()).stream()
                .collect(Collectors.toMap(DeploymentEnvironmentView::id, DeploymentEnvironmentView::name));
        return promotionRepository.findAllByChangeSet_IdOrderBySubmittedAtDesc(changeSetId).stream()
                .map(p -> withoutSnapshot(toView(p, names.get(p.getEnvironmentId()))))
                .toList();
    }

    @Override
    @Transactional
    public void cancel(UUID organizationId, UUID actorId, UUID promotionId) {
        // Under the same row lock the projection listener takes: otherwise a concurrent transition
        // bumps @Version and the flush below fails as an unmapped optimistic-lock error (500).
        var promotion = promotionRepository.findByIdAndOrganizationIdForUpdate(promotionId, organizationId)
                .orElseThrow(() -> new SchemaChangePromotionNotFoundException(promotionId));
        var current = promotion.getStatus();
        if (current.isTerminal()) {
            throw new SchemaChangePromotionNotCancellableException(promotionId, current);
        }
        // The group's cancel is submitter-only, so it is called as the promoter; the requestgroups
        // audit row therefore names the promoter while ours below names the real actor.
        try {
            requestGroupService.cancel(promotion.getRequestGroupId(), organizationId, promotion.getPromotedBy());
        } catch (IllegalRequestGroupStateException ex) {
            throw new SchemaChangePromotionNotCancellableException(promotionId, current);
        }
        promotion.setStatus(SchemaChangePromotionStatus.CANCELLED);
        promotionRepository.saveAndFlush(promotion);
        auditWriter.record(AuditAction.SCHEMA_CHANGE_PROMOTION_CANCELLED, promotionId, organizationId, actorId,
                Map.of("request_group_id", promotion.getRequestGroupId().toString(),
                        "cancelled_on_behalf_of_submitter", true),
                null, null);
        eventPublisher.publishEvent(new SchemaChangePromotionStatusChangedEvent(promotionId,
                promotion.getChangeSet().getId(), promotion.getEnvironmentId(), organizationId,
                promotion.getPromotedBy(), current, SchemaChangePromotionStatus.CANCELLED));
    }

    private SchemaChangeSetEntity requireChangeSet(UUID organizationId, UUID changeSetId) {
        return changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId)
                .orElseThrow(() -> new SchemaChangeSetNotFoundException(changeSetId));
    }

    private SchemaChangeSetPromotionEntity requirePromotion(UUID organizationId, UUID promotionId) {
        return promotionRepository.findByIdAndOrganizationId(promotionId, organizationId)
                .orElseThrow(() -> new SchemaChangePromotionNotFoundException(promotionId));
    }

    /** The binding is a bare id: a deleted datasource stays bound and must refuse, never be skipped. */
    private void requireDatasource(UUID organizationId, UUID pipelineId, UUID datasourceId) {
        try {
            datasourceAdminService.getForAdmin(datasourceId, organizationId);
        } catch (DatasourceNotFoundException ex) {
            throw new SchemaChangeSetTargetDatasourceMissingException(pipelineId, datasourceId);
        }
    }

    /** {@code can_ddl} for everyone — there is deliberately no admin flag on this path. */
    private void requireDdl(UUID actorId, UUID datasourceId) {
        var canDdl = permissionLookupService.findFor(actorId, datasourceId)
                .map(DatasourceUserPermissionView::canDdl)
                .orElse(false);
        if (!canDdl) {
            throw new SchemaChangePromotionDdlForbiddenException(datasourceId);
        }
    }

    /**
     * Every lower rung that binds a datasource must hold an {@code APPLIED} promotion of this set.
     * The ladder is asserted, not trusted: over duplicate sort orders "lower" is undefined and a
     * gate over an all-equal column would pass vacuously — the one failure mode this must not have.
     */
    private void requireLadder(SchemaChangeSetEntity changeSet, DeploymentEnvironmentView target) {
        var ladder = environmentLookupService.listByPipeline(changeSet.getPipelineId());
        var orders = new HashSet<Integer>();
        for (var rung : ladder) {
            if (!orders.add(rung.sortOrder())) {
                throw new SchemaChangePromotionLadderInvalidException(changeSet.getPipelineId());
            }
        }
        for (var rung : ladder) {
            if (rung.sortOrder() >= target.sortOrder() || rung.datasourceId() == null) {
                continue;
            }
            if (!promotionRepository.existsByChangeSet_IdAndEnvironmentIdAndStatus(
                    changeSet.getId(), rung.id(), SchemaChangePromotionStatus.APPLIED)) {
                throw new SchemaChangePromotionLadderBlockedException(changeSet.getId(), rung.id(), rung.name());
            }
        }
    }

    /** Any active window refuses — HOLD included, because HOLD is also what an unevaluable window yields. */
    private void requireNotFrozen(UUID organizationId, UUID pipelineId, DeploymentEnvironmentView environment) {
        freezeLookupService.evaluate(organizationId, pipelineId, environment.id()).ifPresent(freeze -> {
            throw new SchemaChangePromotionFrozenException(environment.id(), freeze.windowId(), freeze.behavior(),
                    freeze.reason());
        });
    }

    /**
     * Group review strictness comes from the target datasource's plan alone; a datasource without a
     * human-approval plan auto-approves. An environment that requires review therefore refuses a
     * target that cannot deliver one, rather than promoting silently under weaker rules.
     */
    private void requireEnforceableReview(DeploymentEnvironmentView environment, UUID datasourceId) {
        if (!environment.requireReview()) {
            return;
        }
        var enforceable = reviewPlanLookupService.findForDatasource(datasourceId)
                .map(ReviewPlanSnapshot::requiresHumanApproval)
                .orElse(false);
        if (!enforceable) {
            throw new SchemaChangePromotionReviewUnenforceableException(environment.id(), datasourceId);
        }
    }

    /** The pre-check loses a race between two promoters; the partial unique index does not. */
    private SchemaChangeSetPromotionEntity savePromotion(SchemaChangeSetPromotionEntity promotion) {
        try {
            return promotionRepository.saveAndFlush(promotion);
        } catch (DataIntegrityViolationException ex) {
            var cause = ex.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains(OPEN_CONSTRAINT)) {
                throw new SchemaChangePromotionConflictException(promotion.getChangeSet().getId(),
                        promotion.getEnvironmentId());
            }
            throw ex;
        }
    }

    /** One QUERY member per statement, in authored order — the group numbers members by list position. */
    static List<RequestGroupItemInput> toItems(UUID datasourceId, List<SchemaChangeSetStatementEntity> statements) {
        return statements.stream()
                .map(s -> new RequestGroupItemInput(RequestGroupTargetKind.QUERY, s.getSequenceOrder(), datasourceId,
                        s.getSqlText(), false, null, null, null, null, null, null, null, null, null, null, null))
                .toList();
    }

    static String groupName(String changeSetName, String environmentName) {
        var name = "schema-change:" + changeSetName + "@" + environmentName;
        return name.length() <= GROUP_NAME_MAX ? name : name.substring(0, GROUP_NAME_MAX);
    }

    private SchemaChangePromotionView toView(SchemaChangeSetPromotionEntity p) {
        var environmentName = environmentLookupService.findById(p.getEnvironmentId())
                .map(DeploymentEnvironmentView::name)
                .orElse(null);
        return toView(p, environmentName);
    }

    private static SchemaChangePromotionView withoutSnapshot(SchemaChangePromotionView view) {
        return new SchemaChangePromotionView(view.id(), view.organizationId(), view.changeSetId(),
                view.environmentId(), view.environmentName(), view.datasourceId(), view.requestGroupId(),
                view.status(), view.statementsChecksum(), view.promotedBy(), view.submittedAt(), view.appliedAt(),
                view.errorMessage(), null, view.snapshotTakenAt());
    }

    static SchemaChangePromotionView toView(SchemaChangeSetPromotionEntity p, String environmentName) {
        return new SchemaChangePromotionView(p.getId(), p.getOrganizationId(), p.getChangeSet().getId(),
                p.getEnvironmentId(), environmentName, p.getDatasourceId(), p.getRequestGroupId(), p.getStatus(),
                p.getStatementsChecksum(), p.getPromotedBy(), p.getSubmittedAt(), p.getAppliedAt(),
                p.getErrorMessage(), p.getSchemaSnapshot(), p.getSnapshotTakenAt());
    }
}
