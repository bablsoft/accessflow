package com.bablsoft.accessflow.ai.api;

/**
 * Outcome of an admin-triggered Langfuse connectivity check against the org's saved credentials.
 * {@code message} is a human-readable status (localized for the fixed cases, the raw error
 * otherwise).
 */
public record LangfuseConnectionTestResult(boolean success, String message) {
}
