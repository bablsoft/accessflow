package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiReviewDecisionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.DelegatedIdentity;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewCandidate;
import com.bablsoft.accessflow.core.api.ReviewDelegationLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiReviewService implements ApiReviewService {

    private static final int STAGE = 1;

    private final ApiRequestRepository requestRepository;
    private final ApiReviewDecisionRepository decisionRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiRequestStateService stateService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewDelegationLookupService reviewDelegationLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PendingApiReview> listPending(ReviewerContext context,
                                                      PendingApiReviewFilter filter, PageRequest pageRequest) {
        if (!hasPermission(context, Permission.API_REQUEST_REVIEW)) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var unrestricted = hasPermission(context, Permission.REVIEW_OVERRIDE);
        var spec = ApiRequestSpecifications.forPendingReview(context.organizationId(), context.userId(),
                filter.connectorId(), filter.verb(), unrestricted,
                unrestricted ? List.of() : reviewReaches(context));
        var page = requestRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size()));
        var content = page.getContent().stream().map(this::toPending).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize() <= 0 ? 1 : page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public DecisionOutcome approve(UUID apiRequestId, ReviewerContext context, String comment) {
        var request = require(apiRequestId, context.organizationId());
        var candidate = guardReviewable(request, context);
        var existing = decisionRepository.findByApiRequestIdAndReviewerIdAndStage(
                apiRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(apiRequestId, context.userId(), DecisionType.APPROVED, comment,
                candidate);
        long approvals = decisionRepository.countByApiRequestIdAndStageAndDecision(
                apiRequestId, STAGE, DecisionType.APPROVED);
        QueryStatus resulting = request.getStatus();
        if (approvals >= request.getRequiredApprovals()) {
            stateService.apply(request, QueryStatus.APPROVED);
            eventPublisher.publishEvent(new ApiRequestDecidedEvent(apiRequestId, QueryStatus.APPROVED, null));
            resulting = QueryStatus.APPROVED;
        }
        return new DecisionOutcome(decision.getId(), DecisionType.APPROVED, resulting, false);
    }

    @Override
    @Transactional
    public DecisionOutcome reject(UUID apiRequestId, ReviewerContext context, String comment) {
        var request = require(apiRequestId, context.organizationId());
        var candidate = guardReviewable(request, context);
        var existing = decisionRepository.findByApiRequestIdAndReviewerIdAndStage(
                apiRequestId, context.userId(), STAGE);
        if (existing.isPresent()) {
            return new DecisionOutcome(existing.get().getId(), existing.get().getDecision(),
                    request.getStatus(), true);
        }
        var decision = record(apiRequestId, context.userId(), DecisionType.REJECTED, comment,
                candidate);
        stateService.apply(request, QueryStatus.REJECTED);
        eventPublisher.publishEvent(new ApiRequestDecidedEvent(apiRequestId, QueryStatus.REJECTED, null));
        return new DecisionOutcome(decision.getId(), DecisionType.REJECTED, QueryStatus.REJECTED, false);
    }

    /**
     * Resolves the identity the caller may decide this request under (#622).
     *
     * <p>Before this, eligibility was "holds the permission, same org, not the submitter" — the
     * connector's review plan was consulted only for {@code minApprovalsRequired}, never for who
     * may approve. Approver rules are now honoured, but <strong>opt-in by configuration</strong>:
     * a connector with no review plan, or a plan carrying no approver rules, stays open to any
     * holder of {@code API_REQUEST_REVIEW}, exactly as before. Treating "no plan" as "nobody is an
     * approver" would turn an upgrade into an outage on every un-planned connector.
     */
    private ReviewCandidate guardReviewable(ApiRequestEntity request, ReviewerContext context) {
        if (request.getSubmittedBy().equals(context.userId())) {
            throw new SelfApprovalNotAllowedException();
        }
        if (request.getStatus() != QueryStatus.PENDING_REVIEW) {
            throw new IllegalApiRequestStateException(request.getStatus(),
                    "API request is not awaiting review");
        }
        var self = ReviewCandidate.self(context.userId(), context.roleName());
        // Enforced in the service, not only via @PreAuthorize on the controller: listPending is
        // also reachable from the dashboard module. Deliberately before delegation resolution — a
        // delegation widens which requests a permitted reviewer may act on, never whether they
        // may review at all.
        if (!hasPermission(context, Permission.API_REQUEST_REVIEW)) {
            throw new ApiReviewerNotEligibleException(context.userId(), request.getId());
        }
        if (hasPermission(context, Permission.REVIEW_OVERRIDE)) {
            return self;
        }
        var plan = resolvePlan(request.getConnectorId());
        if (plan == null || plan.approvers().isEmpty()) {
            return self;
        }
        var decided = decisionRepository.findAllByApiRequestIdAndStage(request.getId(), STAGE);
        return candidates(context, request, decided).stream()
                .filter(candidate -> isApproverAtStage(plan, STAGE, candidate))
                .findFirst()
                .orElseThrow(() -> new ApiReviewerNotEligibleException(context.userId(),
                        request.getId()));
    }

    /**
     * The connectors each of the caller's identities may review. Computed here rather than in SQL
     * because {@code apigov} cannot reference {@code core}'s approver entities; the connector
     * catalog is small and already loaded whole elsewhere, so this costs one connector list plus
     * one plan lookup per distinct plan.
     *
     * <p>A connector with no review plan, or a plan with no approver rules, is reachable by every
     * holder of the permission — the same opt-in rule the decision guard applies. A scoped
     * delegation only reaches the connector it names.
     */
    private List<ApiRequestSpecifications.ReviewReach> reviewReaches(ReviewerContext context) {
        // Every connector, not just active ones: deactivating a connector must not make its
        // already-submitted PENDING_REVIEW requests vanish from the queue. The decision path does
        // not filter on active either, so a filtered queue would leave them decidable-but-hidden
        // until they time out.
        var connectors = connectorRepository.findAllByOrganizationId(context.organizationId());
        var plans = new HashMap<UUID, ReviewPlanSnapshot>();
        for (var connector : connectors) {
            if (connector.getReviewPlanId() != null) {
                plans.computeIfAbsent(connector.getReviewPlanId(),
                        id -> reviewPlanLookupService.findById(id).orElse(null));
            }
        }
        var reaches = new ArrayList<ApiRequestSpecifications.ReviewReach>();
        reaches.add(reachFor(ReviewCandidate.self(context.userId(), context.roleName()), null,
                connectors, plans));
        // Unscoped lookup: the reach spans the whole catalog, so each delegation filters itself
        // per connector rather than being narrowed to one up front.
        for (var delegation : reviewDelegationLookupService.findActiveForDelegate(
                context.organizationId(), context.userId(), null, null)) {
            reaches.add(reachFor(ReviewCandidate.borrowed(delegation), delegation, connectors,
                    plans));
        }
        return reaches;
    }

    private ApiRequestSpecifications.ReviewReach reachFor(ReviewCandidate identity,
                                                          DelegatedIdentity delegation,
                                                          List<ApiConnectorEntity> connectors,
                                                          Map<UUID, ReviewPlanSnapshot> plans) {
        var reachable = new HashSet<UUID>();
        for (var connector : connectors) {
            if (delegation != null
                    && !delegation.covers(DelegationScopeKind.API_CONNECTOR, connector.getId())) {
                continue;
            }
            var plan = connector.getReviewPlanId() == null
                    ? null : plans.get(connector.getReviewPlanId());
            if (plan == null || plan.approvers().isEmpty()
                    || isApproverAtStage(plan, STAGE, identity)) {
                reachable.add(connector.getId());
            }
        }
        return new ApiRequestSpecifications.ReviewReach(identity.onBehalfOfUserId(), reachable);
    }

    private List<ReviewCandidate> candidates(ReviewerContext context, ApiRequestEntity request,
                                             List<ApiReviewDecisionEntity> decided) {
        var delegations = reviewDelegationLookupService.findActiveForDelegate(
                context.organizationId(), context.userId(), DelegationScopeKind.API_CONNECTOR,
                request.getConnectorId());
        var candidates = new ArrayList<ReviewCandidate>();
        candidates.add(ReviewCandidate.self(context.userId(), context.roleName()));
        delegations.stream().map(ReviewCandidate::borrowed).forEach(candidates::add);
        // The self-approval ban covers both identities: a delegate may not act on a request the
        // delegator submitted. Drop that identity rather than reject outright — the caller may
        // still qualify in their own right.
        candidates.removeIf(candidate -> candidate.isDelegated()
                && request.getSubmittedBy().equals(candidate.onBehalfOfUserId()));
        // One authority, one vote. The unique index only stops the acting user voting twice; it
        // cannot see that a delegator already voted personally, or the reverse.
        //
        // Decisions the acting user cast themselves are excluded: that is a replay, answered
        // idempotently by the existing-decision check in approve/reject. Without the exclusion a
        // retry would be rejected as ineligible before ever reaching it.
        candidates.removeIf(candidate -> decided.stream()
                .filter(decision -> !context.userId().equals(decision.getReviewerId()))
                .anyMatch(decision -> candidate.userId().equals(decision.getReviewerId())
                        || candidate.userId().equals(decision.getOnBehalfOfUserId())));
        return candidates;
    }

    private ReviewPlanSnapshot resolvePlan(UUID connectorId) {
        if (connectorId == null) {
            return null;
        }
        return connectorRepository.findById(connectorId)
                .map(ApiConnectorEntity::getReviewPlanId)
                .flatMap(reviewPlanLookupService::findById)
                .orElse(null);
    }

    private static boolean isApproverAtStage(ReviewPlanSnapshot plan, int stage,
                                             ReviewCandidate candidate) {
        return plan.approvers().stream()
                .filter(rule -> rule.stage() == stage)
                .anyMatch(rule -> matchesUser(rule, candidate) || matchesRole(rule, candidate));
    }

    private static boolean matchesUser(ApproverRule rule, ReviewCandidate candidate) {
        return rule.userId() != null && rule.userId().equals(candidate.userId());
    }

    private static boolean matchesRole(ApproverRule rule, ReviewCandidate candidate) {
        return rule.role() != null && rule.role().equalsIgnoreCase(candidate.roleName());
    }

    private static boolean hasPermission(ReviewerContext context, Permission permission) {
        return context.permissions() != null && context.permissions().contains(permission);
    }

    private ApiReviewDecisionEntity record(UUID apiRequestId, UUID reviewerId, DecisionType decision,
                                           String comment, ReviewCandidate candidate) {
        var entity = new ApiReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setApiRequestId(apiRequestId);
        entity.setReviewerId(reviewerId);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(STAGE);
        // #622: reviewer_id stays the acting human; these name the borrowed authority.
        entity.setOnBehalfOfUserId(candidate.onBehalfOfUserId());
        entity.setDelegationId(candidate.delegationId());
        return decisionRepository.save(entity);
    }

    private ApiRequestEntity require(UUID id, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ApiRequestNotFoundException(id));
    }

    /** Counts the keys in the persisted overrides jsonb without materializing a map per row. */
    private int variableOverrideCount(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return 0;
        }
        try {
            var node = objectMapper.readTree(json);
            return node.isObject() ? node.size() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private PendingApiReview toPending(ApiRequestEntity e) {
        var connectorName = connectorRepository.findById(e.getConnectorId())
                .map(ApiConnectorEntity::getName).orElse(null);
        var summary = e.getAiAnalysisId() != null
                ? aiAnalysisLookupService.findById(e.getAiAnalysisId()).orElse(null) : null;
        return new PendingApiReview(e.getId(), e.getConnectorId(), connectorName, e.getSubmittedBy(),
                e.getVerb(), e.getRequestPath(), e.isWrite(), e.getJustification(), e.getAiAnalysisId(),
                summary != null ? summary.riskLevel() : null,
                summary != null ? summary.riskScore() : null,
                summary != null ? summary.summary() : null, STAGE,
                variableOverrideCount(e.getVariableOverrides()), e.getCreatedAt());
    }
}
