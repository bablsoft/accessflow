package com.bablsoft.accessflow.core.api;

/**
 * Why a query request was submitted. {@code USER_SUBMITTED} is the default (a human authored the SQL);
 * {@code AI_SUGGESTION} marks a draft created by applying an AI optimization suggestion, so the audit
 * trail records the origin.
 */
public enum SubmissionReason {
    USER_SUBMITTED,
    AI_SUGGESTION
}
