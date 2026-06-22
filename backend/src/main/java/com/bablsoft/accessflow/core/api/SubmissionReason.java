package com.bablsoft.accessflow.core.api;

/**
 * Why a query request was submitted. {@code USER_SUBMITTED} is the default (a human authored the SQL);
 * {@code AI_SUGGESTION} marks a draft created by applying an AI optimization suggestion;
 * {@code EMERGENCY_ACCESS} marks a query that bypassed pre-approval through the break-glass path
 * (AF-385). The audit trail records the origin.
 */
public enum SubmissionReason {
    USER_SUBMITTED,
    AI_SUGGESTION,
    EMERGENCY_ACCESS
}
