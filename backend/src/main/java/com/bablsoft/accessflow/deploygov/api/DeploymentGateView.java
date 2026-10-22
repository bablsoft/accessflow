package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The gate's answer for one deployment request (#693). {@code releasable} is computed by a single
 * fail-closed pure function; {@code frozen}/{@code freezeReason} describe the winning freeze
 * window when one is active (always {@code false}/{@code null} for a break-glass request — the
 * freeze check is skipped). {@code aiRiskLevel} is null when no analysis produced a risk.
 * {@code changeTicketRequired} mirrors the environment flag (#993); {@code changeTicket} is the
 * linked ticket the gate judged by — rejected first, else approved, else the newest — or null.
 */
public record DeploymentGateView(
        UUID requestId,
        QueryStatus status,
        boolean releasable,
        int requiredApprovals,
        int grantedApprovals,
        List<DeploymentReviewDecisionView> decisions,
        boolean frozen,
        String freezeReason,
        Instant scheduledFor,
        RiskLevel aiRiskLevel,
        boolean changeTicketRequired,
        DeploymentTicketView changeTicket) {

    public DeploymentGateView {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
