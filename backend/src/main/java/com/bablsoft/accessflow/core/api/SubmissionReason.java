package com.bablsoft.accessflow.core.api;

/**
 * Why a query request was submitted. {@code USER_SUBMITTED} is the default (a human authored the SQL);
 * {@code AI_SUGGESTION} marks a draft created by applying an AI optimization suggestion;
 * {@code EMERGENCY_ACCESS} marks a query that bypassed pre-approval through the break-glass path
 * (AF-385); {@code RECURRING} marks a child occurrence row created by the recurring-query job for an
 * approved recurring series (#627) — never user-submitted; {@code HISTORY_SUGGESTION} marks a draft
 * created by applying an automatic query suggestion mined from the organisation's own approved
 * history (#776). The audit trail records the origin.
 *
 * <p>{@code HISTORY_SUGGESTION} is deliberately distinct from {@code AI_SUGGESTION} rather than
 * folded into it: the two measure different things, and merging them would silently corrupt the
 * AI-suggestion adoption signal the AF-498 dashboard reads. Like {@code AI_SUGGESTION} it is
 * client-supplied provenance, so — unlike {@code RECURRING} and {@code EMERGENCY_ACCESS} — it is
 * not one of the reserved reasons the submission service rejects.
 */
public enum SubmissionReason {
    USER_SUBMITTED,
    AI_SUGGESTION,
    EMERGENCY_ACCESS,
    RECURRING,
    HISTORY_SUGGESTION
}
