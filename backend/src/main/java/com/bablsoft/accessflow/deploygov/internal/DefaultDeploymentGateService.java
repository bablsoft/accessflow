package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateService;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateView;
import com.bablsoft.accessflow.deploygov.api.DeploymentNotReleasableException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewDecisionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketView;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentReleasableEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentTicketEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The deployment gate (#693). Releasability is computed by the single pure function
 * {@link #releasable(QueryStatus, boolean, Instant, Instant, boolean, boolean)} — default answer
 * not-releasable — and every freeze/schedule/ticket evaluation is wrapped so that <em>any</em>
 * lookup or evaluation error fails closed. {@code frozen} means any active window, {@code HOLD} or {@code REJECT}: a
 * {@code REJECT} window normally auto-rejects at submission, but a request approved before the
 * window started must not sail through mid-freeze. Break-glass requests skip the freeze check —
 * they already bypassed it at submission — and skip the change-ticket conjunct (#993) too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultDeploymentGateService implements DeploymentGateService {

    /** {@code deployment_tickets.decision} values — strings, the enum lives in notifications. */
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentReviewDecisionRepository decisionRepository;
    private final DeploymentTicketRepository ticketRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;
    private final FreezeWindowEvaluator freezeWindowEvaluator;
    private final DeploymentRequestStateService stateService;
    private final DefaultDeploymentRequestService requestService;
    private final DeploygovAuditWriter auditWriter;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DeploymentGateView gate(String pipelineName, String environmentName, String version,
                                   UUID organizationId, UUID callerId,
                                   Set<Permission> callerPermissions) {
        var pipeline = resolvePipeline(organizationId, pipelineName);
        var environment = environmentRepository
                .findByPipelineIdAndNameIgnoreCase(pipeline.getId(), environmentName)
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(
                        pipeline.getId(), environmentName));
        var request = requestRepository
                .findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                        pipeline.getId(), environment.getId(), version)
                .stream().findFirst()
                .orElseThrow(() -> new DeploymentRequestNotFoundException(
                        pipeline.getId(), environment.getId(), version));
        requireVisible(request, callerId, callerPermissions);
        return toGateView(request);
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentGateView gateByRequestId(UUID requestId, UUID organizationId, UUID callerId,
                                              Set<Permission> callerPermissions) {
        var request = require(requestId, organizationId);
        requireVisible(request, callerId, callerPermissions);
        return toGateView(request);
    }

    @Override
    @Transactional
    public DeploymentRequestView confirmExecution(UUID requestId, UUID organizationId,
                                                  UUID callerId, Set<Permission> callerPermissions,
                                                  String ipAddress) {
        var request = require(requestId, organizationId);
        requireActor(request, callerId, callerPermissions);
        if (request.getStatus() == QueryStatus.EXECUTED) {
            // Idempotent: a redelivered confirmation must not 409.
            return requestService.detailView(request);
        }
        if (request.getStatus() != QueryStatus.APPROVED) {
            throw new IllegalDeploymentRequestStateException(request.getStatus(),
                    "Deployment execution can only be confirmed from APPROVED");
        }
        var evaluation = evaluate(request, clock.instant());
        if (!evaluation.releasable()) {
            throw new DeploymentNotReleasableException(request.getId(), request.getStatus());
        }
        stateService.apply(request, QueryStatus.EXECUTED);
        // Metadata keys mirror DEPLOYMENT_BREAK_GLASS_EXECUTED (#692): `environment` is the name.
        var environmentName = environmentRepository.findById(request.getEnvironmentId())
                .map(DeploymentEnvironmentEntity::getName)
                .orElse(request.getEnvironmentId().toString());
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "pipeline");
        metadata.put("pipeline_id", request.getPipelineId().toString());
        metadata.put("environment", environmentName);
        metadata.put("version", request.getVersion());
        if (evaluation.ticketRequired()) {
            metadata.put("change_ticket_required", true);
            if (request.getSubmissionReason() == SubmissionReason.EMERGENCY_ACCESS) {
                metadata.put("change_ticket_bypassed", "break_glass");
            } else {
                metadata.put("change_ticket_key", evaluation.changeTicket().externalKey());
            }
        }
        auditWriter.record(AuditAction.DEPLOYMENT_EXECUTED, AuditResourceType.DEPLOYMENT_REQUEST,
                request.getId(), organizationId, callerId, metadata, ipAddress);
        return requestService.detailView(request);
    }

    /**
     * The release job's per-row unit: stamp {@code release_notified_at} and publish
     * {@link DeploymentReleasableEvent} once the request is observably releasable. Transactional so
     * the event publishes inside a real transaction; returns {@code false} when the row was
     * decided, already announced, or is (still) not releasable — a lost race, not an error.
     */
    @Transactional
    public boolean markReleasable(UUID requestId) {
        var request = requestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.APPROVED
                || request.getReleaseNotifiedAt() != null) {
            return false;
        }
        var now = clock.instant();
        if (!evaluate(request, now).releasable()) {
            return false;
        }
        request.setReleaseNotifiedAt(now);
        requestRepository.save(request);
        eventPublisher.publishEvent(new DeploymentReleasableEvent(request.getOrganizationId(),
                request.getId(), request.getPipelineId(), request.getEnvironmentId(),
                request.getVersion()));
        return true;
    }

    /**
     * The one pure releasability function. The default answer is not-releasable: only an
     * {@code APPROVED}, unfrozen request whose scheduled moment (if any) has passed — and, on a
     * {@code change_ticket_required} environment, whose change ticket is approved — opens the gate.
     */
    static boolean releasable(QueryStatus status, boolean frozen, Instant scheduledFor,
                              Instant now, boolean ticketRequired, boolean ticketSatisfied) {
        if (status != QueryStatus.APPROVED || frozen || (ticketRequired && !ticketSatisfied)) {
            return false;
        }
        return scheduledFor == null || !scheduledFor.isAfter(now);
    }

    /**
     * Freeze + schedule + change-ticket evaluation, fail closed: any {@code RuntimeException}
     * answers not-releasable rather than propagating an accidental "yes".
     */
    GateEvaluation evaluate(DeploymentRequestEntity request, Instant now) {
        try {
            var environment = environmentRepository.findById(request.getEnvironmentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Environment " + request.getEnvironmentId() + " not found"));
            boolean ticketRequired = environment.isChangeTicketRequired();
            boolean frozen = false;
            String freezeReason = null;
            boolean ticketSatisfied = true;
            DeploymentTicketView changeTicket = null;
            if (request.getSubmissionReason() != SubmissionReason.EMERGENCY_ACCESS) {
                var freeze = freezeWindowEvaluator.evaluate(request.getOrganizationId(),
                        request.getPipelineId(), request.getEnvironmentId());
                frozen = freeze.isPresent();
                freezeReason = freeze.map(FreezeWindowEvaluator.ActiveFreeze::reason).orElse(null);
                if (ticketRequired) {
                    var tickets = ticketRepository.findAllByDeploymentRequestId(request.getId());
                    ticketSatisfied = ticketApproved(tickets);
                    changeTicket = chooseTicket(tickets);
                }
            }
            return new GateEvaluation(
                    releasable(request.getStatus(), frozen, request.getScheduledFor(), now,
                            ticketRequired, ticketSatisfied),
                    frozen, freezeReason, ticketRequired, changeTicket);
        } catch (RuntimeException ex) {
            log.error("Gate evaluation failed for deployment request {} — answering not releasable",
                    request.getId(), ex);
            return new GateEvaluation(false, false, null, false, null);
        }
    }

    /** At least one linked ticket approved and none rejected; unresolved tickets count for nothing. */
    static boolean ticketApproved(List<DeploymentTicketEntity> tickets) {
        boolean approved = false;
        for (var ticket : tickets) {
            if (REJECTED.equals(ticket.getDecision())) {
                return false;
            }
            approved |= APPROVED.equals(ticket.getDecision());
        }
        return approved;
    }

    /** The newest approved ticket when one exists, else the newest linked ticket, else null. */
    private static DeploymentTicketView chooseTicket(List<DeploymentTicketEntity> tickets) {
        var newestFirst = Comparator.comparing(DeploymentTicketEntity::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
        return tickets.stream()
                .filter(t -> APPROVED.equals(t.getDecision()))
                .min(newestFirst)
                .or(() -> tickets.stream().min(newestFirst))
                .map(DefaultDeploymentTicketService::toView)
                .orElse(null);
    }

    record GateEvaluation(boolean releasable, boolean frozen, String freezeReason,
                          boolean ticketRequired, DeploymentTicketView changeTicket) {
    }

    private DeploymentGateView toGateView(DeploymentRequestEntity request) {
        var evaluation = evaluate(request, clock.instant());
        var decisions = decisionRepository
                .findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(request.getId()).stream()
                .map(d -> new DeploymentReviewDecisionView(d.getId(), d.getReviewerId(),
                        d.getDecision(), d.getComment(), d.getStage(), d.getDecidedAt()))
                .toList();
        int granted = (int) decisionRepository.countByDeploymentRequestIdAndStageAndDecision(
                request.getId(), 1, DecisionType.APPROVED);
        RiskLevel aiRiskLevel = request.getAiAnalysisId() == null ? null
                : aiAnalysisLookupService.findById(request.getAiAnalysisId())
                        .map(AiAnalysisSummaryView::riskLevel).orElse(null);
        return new DeploymentGateView(request.getId(), request.getStatus(),
                evaluation.releasable(), request.getRequiredApprovals(), granted, decisions,
                evaluation.frozen(), evaluation.freezeReason(), request.getScheduledFor(),
                aiRiskLevel, evaluation.ticketRequired(), evaluation.changeTicket());
    }

    /**
     * Case-insensitive name resolution with a deterministic winner: org-name uniqueness is
     * case-sensitive (V149), so {@code deploy-api} and {@code Deploy-API} may coexist — the
     * exact-case match wins, else the alphabetically first. A single-row ignore-case finder would
     * throw on that pair and turn the gate into a permanent 500 for both pipelines.
     */
    private DeploymentPipelineEntity resolvePipeline(UUID organizationId, String pipelineName) {
        var matches = pipelineRepository
                .findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(organizationId, pipelineName);
        if (matches.isEmpty()) {
            throw new DeploymentPipelineNotFoundException(pipelineName);
        }
        return matches.stream()
                .filter(p -> p.getName().equals(pipelineName))
                .findFirst()
                .orElse(matches.getFirst());
    }

    private DeploymentRequestEntity require(UUID requestId, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(requestId, organizationId)
                .orElseThrow(() -> new DeploymentRequestNotFoundException(requestId));
    }

    /**
     * Gate visibility — 404 on failure, never 403: an under-permissioned poll must read exactly
     * like an unknown tuple, and the CI wrappers treat both as not-releasable.
     */
    private void requireVisible(DeploymentRequestEntity request, UUID callerId,
                                Set<Permission> callerPermissions) {
        if (request.getSubmittedBy().equals(callerId) || canViewAll(callerPermissions)
                || canTrigger(request.getPipelineId(), callerId)) {
            return;
        }
        throw new DeploymentRequestNotFoundException(request.getId());
    }

    /** Actor rule for the mutating endpoints: submitter, {@code can_trigger} holder, or admin. */
    private void requireActor(DeploymentRequestEntity request, UUID callerId,
                              Set<Permission> callerPermissions) {
        if (request.getSubmittedBy().equals(callerId)
                || (callerPermissions != null && callerPermissions.contains(Permission.QUERY_ADMIN))
                || canTrigger(request.getPipelineId(), callerId)) {
            return;
        }
        throw new DeploymentRequestPermissionException(
                "Caller may not act on this deployment request");
    }

    private static boolean canViewAll(Set<Permission> callerPermissions) {
        return callerPermissions != null
                && (callerPermissions.contains(Permission.DEPLOYMENT_REVIEW)
                        || callerPermissions.contains(Permission.QUERY_ADMIN));
    }

    private boolean canTrigger(UUID pipelineId, UUID callerId) {
        return permissionResolver.resolve(pipelineId, callerId)
                .map(EffectiveDeploymentPermission::canTrigger)
                .orElse(false);
    }
}
