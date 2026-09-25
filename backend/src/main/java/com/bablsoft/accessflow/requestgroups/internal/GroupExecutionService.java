package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.ByteSizeFormat;
import com.bablsoft.accessflow.core.api.BytesScannedCapExceededException;
import com.bablsoft.accessflow.core.api.BytesScannedCapResolutionService;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.GroupReviewDecisionRepository;
import com.bablsoft.accessflow.core.api.BytesScannedCapOutcome;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupItemExecutedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs an APPROVED group's members in {@code sequence_order}. Query members go through the proxy
 * {@link QueryExecutor} (row-security + masking applied as for a normal query); API members go through
 * the {@link ApiInlineExecutionService}. On the first failure with {@code continue_on_error=false} the
 * run stops, remaining members are marked {@code SKIPPED}, and the group becomes
 * {@code PARTIALLY_EXECUTED} (or {@code FAILED} if the very first member failed). With
 * {@code continue_on_error=true} every member runs and the group becomes {@code EXECUTED} with mixed
 * item statuses. There is <strong>no cross-target rollback</strong> — already-applied members stay.
 */
@Service
@RequiredArgsConstructor
public class GroupExecutionService {

    private static final Logger log = LoggerFactory.getLogger(GroupExecutionService.class);

    private final RequestGroupRepository groupRepository;
    private final RequestGroupItemRepository itemRepository;
    private final RequestGroupStateService stateService;
    private final QueryParser queryParser;
    private final QueryExecutor queryExecutor;
    private final DatasourceLookupService datasourceLookupService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    private final ApiInlineExecutionService apiInlineExecutionService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final BytesScannedCapResolutionService bytesScannedCapResolutionService;
    private final MessageSource messageSource;
    private final QueryCostEstimateService queryCostEstimateService;
    private final GroupReviewDecisionRepository decisionRepository;

    /** Execute an APPROVED group. Idempotent: silently returns if it is not APPROVED (or not yet due). */
    public void execute(UUID groupId, UUID actorUserId, String trigger) {
        var group = groupRepository.findById(groupId).orElse(null);
        if (group == null || group.getStatus() != RequestGroupStatus.APPROVED) {
            return;
        }
        if (group.getScheduledFor() != null && group.getScheduledFor().isAfter(Instant.now())
                && "scheduled".equals(trigger)) {
            return;
        }
        var actor = actorUserId != null ? actorUserId : group.getSubmittedBy();
        group.setExecutionStartedAt(Instant.now());
        stateService.apply(group, RequestGroupStatus.EXECUTING);

        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(groupId);
        boolean stopped = false;
        boolean anyFailed = false;
        int executed = 0;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (stopped) {
                item.setStatus(RequestGroupItemStatus.SKIPPED);
                itemRepository.save(item);
                publishItem(group, item);
                continue;
            }
            runMember(group, item, actor);
            if (item.getStatus() == RequestGroupItemStatus.EXECUTED) {
                executed++;
            } else if (item.getStatus() == RequestGroupItemStatus.FAILED) {
                anyFailed = true;
                if (!group.isContinueOnError()) {
                    stopped = true;
                }
            }
            publishItem(group, item);
        }

        var fresh = groupRepository.findById(groupId).orElse(group);
        fresh.setExecutionCompletedAt(Instant.now());
        RequestGroupStatus finalStatus;
        AuditAction action;
        if (stopped) {
            finalStatus = executed == 0 ? RequestGroupStatus.FAILED
                    : RequestGroupStatus.PARTIALLY_EXECUTED;
            action = executed == 0 ? AuditAction.REQUEST_GROUP_FAILED
                    : AuditAction.REQUEST_GROUP_PARTIALLY_EXECUTED;
        } else if (anyFailed && executed == 0) {
            finalStatus = RequestGroupStatus.FAILED;
            action = AuditAction.REQUEST_GROUP_FAILED;
        } else {
            finalStatus = RequestGroupStatus.EXECUTED;
            action = AuditAction.REQUEST_GROUP_EXECUTED;
        }
        stateService.apply(fresh, finalStatus);
        audit(action, fresh, actor, Map.of(
                "trigger", trigger, "executed_members", executed, "total_members", items.size()));
    }

    private void runMember(RequestGroupEntity group, RequestGroupItemEntity item, UUID actorUserId) {
        var start = Instant.now();
        try {
            if (item.getTargetKind() == RequestGroupTargetKind.QUERY) {
                runQuery(group, item);
            } else {
                runApi(group, item);
            }
        } catch (RuntimeException ex) {
            log.warn("Group {} member {} (seq {}) failed: {}", group.getId(), item.getId(),
                    item.getSequenceOrder(), ex.getMessage());
            item.setStatus(RequestGroupItemStatus.FAILED);
            item.setErrorMessage(ex.getMessage());
        }
        item.setDurationMs((int) java.time.Duration.between(start, Instant.now()).toMillis());
        item.setExecutedAt(Instant.now());
        itemRepository.save(item);
        auditMember(group, item, actorUserId);
    }

    private void runQuery(RequestGroupEntity group, RequestGroupItemEntity item) {
        var permission = permissionLookupService
                .findFor(group.getSubmittedBy(), item.getDatasourceId());
        var restrictedColumns = permission
                .map(p -> p.restrictedColumns())
                .orElse(List.of());
        Integer rowLimitOverride = permission.map(p -> p.rowLimitOverride()).orElse(null);
        var columnMasks = maskingPolicyResolutionService
                .resolveApplicable(group.getOrganizationId(), item.getDatasourceId(), group.getSubmittedBy())
                .stream()
                .map(m -> new ColumnMaskDirective(m.columnRef(), m.strategy(), m.params(), m.policyId()))
                .toList();
        var rowSecurity = rowSecurityResolutionService
                .resolveApplicable(group.getOrganizationId(), item.getDatasourceId(), group.getSubmittedBy())
                .stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();
        var dbType = datasourceLookupService.findById(item.getDatasourceId())
                .map(d -> d.dbType()).orElse(DbType.POSTGRESQL);
        var parsed = queryParser.parse(item.getSqlText(), dbType);
        var appliedRowLimit = rowLimitPolicyResolutionService.resolve(group.getOrganizationId(),
                item.getDatasourceId(), group.getSubmittedBy(), parsed.referencedTables());
        if (appliedRowLimit.isPresent()) {
            rowLimitOverride = appliedRowLimit.get().tighten(rowLimitOverride);
        }
        var request = new QueryExecutionRequest(
                item.getDatasourceId(), item.getSqlText(), item.getQueryType(), rowLimitOverride,
                null, restrictedColumns, columnMasks, rowSecurity, parsed.transactional(),
                parsed.statements(), List.of(), parsed.referencedTables());
        enforceBytesScannedCap(group, item, request);
        var result = queryExecutor.execute(request);
        long rows = switch (result) {
            case SelectExecutionResult select -> select.rowCount();
            case UpdateExecutionResult update -> update.rowsAffected();
        };
        item.setRowsAffected(rows);
        item.setResultSnapshot("rows=" + rows);
        item.setStatus(RequestGroupItemStatus.EXECUTED);
        item.setErrorMessage(null);
    }

    private void runApi(RequestGroupEntity group, RequestGroupItemEntity item) {
        var result = apiInlineExecutionService.executeInline(
                new ApiInlineExecutionService.ApiInlineExecutionCommand(
                        item.getApiConnectorId(), group.getOrganizationId(), group.getSubmittedBy(),
                        item.getOperationId(), item.getVerb(), item.getRequestPath(),
                        item.getRequestHeaders(), item.getQueryParams(),
                        item.getBodyType() == null ? ApiBodyType.RAW : item.getBodyType(),
                        item.getRequestContentType(), item.getRequestBody(), item.getFormFields(),
                        item.getBinaryFilename()));
        item.setResponseStatusCode(result.statusCode());
        item.setResultSnapshot(result.responseSnapshot());
        if (result.success()) {
            item.setStatus(RequestGroupItemStatus.EXECUTED);
            item.setErrorMessage(null);
        } else {
            item.setStatus(RequestGroupItemStatus.FAILED);
            item.setErrorMessage(result.errorMessage() != null ? result.errorMessage()
                    : "Upstream returned HTTP " + result.statusCode());
        }
    }

    private void publishItem(RequestGroupEntity group, RequestGroupItemEntity item) {
        eventPublisher.publishEvent(new RequestGroupItemExecutedEvent(group.getId(), item.getId(),
                group.getSubmittedBy(), item.getSequenceOrder(), item.getStatus()));
    }

    private void auditMember(RequestGroupEntity group, RequestGroupItemEntity item, UUID actorUserId) {
        audit(AuditAction.REQUEST_GROUP_EXECUTED, group, actorUserId, Map.of(
                "item_id", item.getId().toString(),
                "sequence_order", item.getSequenceOrder(),
                "target_kind", item.getTargetKind().name(),
                "member_status", item.getStatus().name()));
    }

    /**
     * A member has no persisted estimate of its own, so when a bytes-scanned cap (#941) binds the
     * submitter the member is dry-run here — only then, so an uncapped group pays nothing. A
     * refusal fails the member like any execution error ({@code continue_on_error} decides the
     * rest). A missing estimate under {@code REQUIRE_REVIEW} passes only when a person approved
     * the group.
     */
    private void enforceBytesScannedCap(RequestGroupEntity group, RequestGroupItemEntity item,
                                        QueryExecutionRequest request) {
        var cap = bytesScannedCapResolutionService
                .resolve(item.getDatasourceId(), group.getSubmittedBy())
                .orElse(null);
        if (cap == null) {
            return;
        }
        var estimated = queryCostEstimateService.estimateBytesScanned(request).orElse(null);
        var outcome = cap.check(estimated);
        // A group can be auto-approved by its plans without anyone looking at it, and the cap is
        // not consulted at that point — so "require review" is only satisfied when a person
        // actually approved the group. Otherwise the member is refused rather than run unreviewed.
        boolean unreviewed = outcome == BytesScannedCapOutcome.NO_ESTIMATE_REVIEW
                && !decisionRepository.existsByRequestGroupIdAndDecision(group.getId(),
                        DecisionType.APPROVED);
        if (!outcome.rejects() && !unreviewed) {
            return;
        }
        var limit = ByteSizeFormat.format(cap.limit());
        var locale = LocaleContextHolder.getLocale();
        String message;
        if (estimated != null) {
            message = messageSource.getMessage("error.bytes_cap.exceeded",
                    new Object[]{ByteSizeFormat.format(estimated), limit}, locale);
        } else if (unreviewed) {
            message = messageSource.getMessage("error.bytes_cap.no_estimate_unreviewed",
                    new Object[]{limit}, locale);
        } else {
            message = messageSource.getMessage("error.bytes_cap.no_estimate", new Object[]{limit},
                    locale);
        }
        var metadata = new HashMap<String, Object>();
        metadata.put("trigger", "bytes_scanned_cap");
        metadata.put("stage", "execution");
        metadata.put("item_id", item.getId().toString());
        metadata.put("limit", cap.limit());
        metadata.put("source", cap.source().name());
        if (estimated != null) {
            metadata.put("estimated_bytes", estimated);
        }
        metadata.put("outcome", outcome.name());
        audit(AuditAction.QUERY_BYTES_SCANNED_CAP_ENFORCED, group, null, metadata);
        throw new BytesScannedCapExceededException(message, cap, estimated, outcome);
    }

    private void audit(AuditAction action, RequestGroupEntity group, UUID actorId,
                       Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.REQUEST_GROUP, group.getId(),
                    group.getOrganizationId(), actorId, metadata, group.getSubmittedIp(),
                    group.getSubmittedUserAgent()));
        } catch (RuntimeException ex) {
            log.warn("Failed to record audit {} for group {}: {}", action, group.getId(), ex.getMessage());
        }
    }
}
