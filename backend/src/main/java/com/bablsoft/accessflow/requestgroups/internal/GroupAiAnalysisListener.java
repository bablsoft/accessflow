package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupItemAnalyzedEvent;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupSubmittedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Drives async, fail-safe per-member AI risk scoring of a submitted group, then routes the group to
 * review or approval. Query members go through {@link AiAnalyzerService#analyzePreview} and API
 * members through {@link ApiAssistService#analyzeDetailed}; both are persisted to
 * {@code ai_analyses} keyed to the member (AF-531), so the group detail view can embed the full
 * analysis. A member whose analysis fails escalates (never blocks). The aggregate group risk is the maximum
 * across members; once every member is scored the group resolves its union-of-plans review
 * requirement and transitions to {@code PENDING_REVIEW} (human approval needed) or {@code APPROVED}.
 */
@Component
@RequiredArgsConstructor
class GroupAiAnalysisListener {

    private static final Logger log = LoggerFactory.getLogger(GroupAiAnalysisListener.class);

    private final RequestGroupRepository groupRepository;
    private final RequestGroupItemRepository itemRepository;
    private final AiAnalyzerService aiAnalyzerService;
    private final ApiAssistService apiAssistService;
    private final AiAnalysisPersistenceService aiAnalysisPersistenceService;
    private final GroupReviewPlanResolver reviewPlanResolver;
    private final RequestGroupStateService stateService;
    private final SqlReviewFindingService sqlReviewFindingService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @ApplicationModuleListener
    void onSubmitted(RequestGroupSubmittedEvent event) {
        var group = groupRepository.findById(event.requestGroupId()).orElse(null);
        if (group == null || group.getStatus() != RequestGroupStatus.PENDING_AI) {
            return;
        }
        var items = itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId());
        RiskLevel maxLevel = null;
        Integer maxScore = null;
        for (RequestGroupItemEntity item : items) {
            analyzeMember(group, item);
            itemRepository.save(item);
            eventPublisher.publishEvent(new RequestGroupItemAnalyzedEvent(group.getId(), item.getId()));
            if (item.getAiRiskLevel() != null
                    && (maxLevel == null || item.getAiRiskLevel().ordinal() > maxLevel.ordinal())) {
                maxLevel = item.getAiRiskLevel();
            }
            if (item.getAiRiskScore() != null && (maxScore == null || item.getAiRiskScore() > maxScore)) {
                maxScore = item.getAiRiskScore();
            }
        }
        group.setAiRiskLevel(maxLevel);
        group.setAiRiskScore(maxScore);
        groupRepository.save(group);
        route(group, items);
    }

    private void analyzeMember(RequestGroupEntity group, RequestGroupItemEntity item) {
        try {
            if (item.getTargetKind() == RequestGroupTargetKind.QUERY) {
                var result = aiAnalyzerService.analyzePreview(item.getDatasourceId(), item.getSqlText(),
                        group.getSubmittedBy(), group.getOrganizationId(), true);
                var analysisId = aiAnalysisPersistenceService.persistForGroupItem(item.getId(),
                        toCommand(result));
                item.setAiAnalysisId(analysisId);
                item.setAiRiskLevel(result.riskLevel());
                item.setAiRiskScore(result.riskScore());
            } else {
                var result = apiAssistService.analyzeDetailed(item.getApiConnectorId(),
                        group.getOrganizationId(), group.getSubmittedBy(), true,
                        new ApiAssistService.AnalyzeInput(item.getOperationId(), item.getVerb(),
                                item.getRequestPath(), item.getRequestBody(), null));
                var analysisId = aiAnalysisPersistenceService.persistForGroupItem(item.getId(),
                        toCommand(result));
                item.setAiAnalysisId(analysisId);
                item.setAiRiskLevel(result.riskLevel());
                item.setAiRiskScore(result.riskScore());
            }
        } catch (RuntimeException ex) {
            // Fail-safe: a failed member analysis escalates (handled by route falling back to review),
            // never blocks the group.
            log.warn("AI analysis failed for group {} member {}: {}", group.getId(), item.getId(),
                    ex.getMessage());
        }
    }

    private void route(RequestGroupEntity group, List<RequestGroupItemEntity> items) {
        boolean requiresReview;
        int requiredApprovals;
        try {
            var resolution = reviewPlanResolver.resolve(group, items);
            requiresReview = resolution.requiresHumanApproval();
            requiredApprovals = Math.max(1, resolution.requiredApprovals());
        } catch (RuntimeException ex) {
            // Fail closed toward review when plan resolution is impossible.
            log.warn("Group {} review-plan resolution failed; routing to review: {}", group.getId(),
                    ex.getMessage());
            requiresReview = true;
            requiredApprovals = 1;
        }
        // A BLOCK SQL review finding on any query member forces the whole group to a human (#864).
        // Read from what submit() persisted, never re-evaluated here. Audited only when it changed
        // the outcome — a group the plans already sent to review owes no SQL_REVIEW_BLOCKED row.
        var blocking = blockingFindings(items);
        boolean suppressedGroupApproval = !requiresReview && !blocking.isEmpty();
        if (suppressedGroupApproval) {
            requiresReview = true;
        }
        if (requiresReview) {
            group.setRequiredApprovals(requiredApprovals);
            groupRepository.save(group);
            stateService.apply(group, RequestGroupStatus.PENDING_REVIEW);
            if (suppressedGroupApproval) {
                auditSqlReviewBlocked(group, blocking);
            }
        } else {
            stateService.apply(group, RequestGroupStatus.APPROVED);
        }
    }

    /** BLOCK findings keyed by member id, in member order; members without one are absent. */
    private LinkedHashMap<UUID, List<String>> blockingFindings(List<RequestGroupItemEntity> items) {
        var byItem = sqlReviewFindingService.findByGroupItems(
                items.stream().map(RequestGroupItemEntity::getId).toList());
        var blocking = new LinkedHashMap<UUID, List<String>>();
        for (var item : items) {
            var ruleIds = byItem.getOrDefault(item.getId(), List.of()).stream()
                    .filter(SqlReviewFinding::isBlocking)
                    .map(SqlReviewFinding::ruleId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!ruleIds.isEmpty()) {
                blocking.put(item.getId(), ruleIds);
            }
        }
        return blocking;
    }

    private void auditSqlReviewBlocked(RequestGroupEntity group, LinkedHashMap<UUID, List<String>> blocking) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "sql_review");
        metadata.put("blocking_rule_ids",
                blocking.values().stream().flatMap(List::stream).distinct().sorted().toList());
        metadata.put("blocking_item_ids", List.copyOf(blocking.keySet()));
        metadata.put("suppressed_paths", List.of("GROUP_REVIEW_PLAN"));
        try {
            auditLogService.record(new AuditEntry(AuditAction.SQL_REVIEW_BLOCKED,
                    AuditResourceType.REQUEST_GROUP, group.getId(), group.getOrganizationId(), null,
                    metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SQL_REVIEW_BLOCKED on group {}", group.getId(), ex);
        }
    }

    private PersistAiAnalysisCommand toCommand(AiAnalysisResult result) {
        return new PersistAiAnalysisCommand(result.aiProvider(), result.aiModel(), result.riskScore(),
                result.riskLevel(), result.summary(), objectMapper.writeValueAsString(result.issues()),
                objectMapper.writeValueAsString(result.optimizations()), result.missingIndexesDetected(),
                result.affectsRowEstimate(), result.promptTokens(), result.completionTokens(), false, null);
    }
}
