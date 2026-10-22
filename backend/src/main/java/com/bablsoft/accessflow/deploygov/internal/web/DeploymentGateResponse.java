package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateView;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The gate's wire shape (#693) — see docs/04-api-spec.md → "Deployment gate & outcome reporting". */
public record DeploymentGateResponse(
        UUID requestId,
        QueryStatus status,
        boolean releasable,
        Approvals approvals,
        List<DeploymentReviewDecisionResponse> decisions,
        boolean frozen,
        String freezeReason,
        Instant scheduledFor,
        RiskLevel aiRiskLevel,
        boolean changeTicketRequired,
        ChangeTicket changeTicket) {

    record Approvals(int required, int granted) {
    }

    record ChangeTicket(String system, String externalKey, String url, String status,
                        String resolution, String decision) {

        static ChangeTicket from(DeploymentTicketView view) {
            return view == null ? null : new ChangeTicket(view.ticketSystem(), view.externalKey(),
                    view.url(), view.status(), view.resolution(), view.decision());
        }
    }

    static DeploymentGateResponse from(DeploymentGateView view) {
        return new DeploymentGateResponse(view.requestId(), view.status(), view.releasable(),
                new Approvals(view.requiredApprovals(), view.grantedApprovals()),
                view.decisions().stream().map(DeploymentReviewDecisionResponse::from).toList(),
                view.frozen(), view.freezeReason(), view.scheduledFor(), view.aiRiskLevel(),
                view.changeTicketRequired(), ChangeTicket.from(view.changeTicket()));
    }
}
