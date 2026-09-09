package com.bablsoft.accessflow.core.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The result of an offline row-security classification (issue AF-630) — the engine's answer to
 * "what would row security do to this query?", produced without executing it. Consumed by the
 * policy simulator so an admin sees which query shapes a draft predicate would deny or fail closed
 * on before the policy is saved.
 *
 * @param outcome         the static verdict
 * @param engineId        the engine that produced it, so a degraded answer can be attributed
 * @param appliedPolicyIds the policies whose predicates would take effect (empty unless
 *                        {@link RowSecurityOutcome#APPLIED} or {@link RowSecurityOutcome#DENY_ALL})
 * @param reason          an already-localized explanation, non-null for
 *                        {@link RowSecurityOutcome#FAIL_CLOSED} and {@link RowSecurityOutcome#UNKNOWN}
 */
public record RowSecurityClassification(RowSecurityOutcome outcome, String engineId,
                                        Set<UUID> appliedPolicyIds, String reason) {

    public RowSecurityClassification {
        Objects.requireNonNull(outcome, "outcome");
        appliedPolicyIds = appliedPolicyIds == null ? Set.of() : Set.copyOf(appliedPolicyIds);
    }

    /** The engine cannot decide offline; the caller must not read this as "safe". */
    public static RowSecurityClassification unknown(String engineId) {
        return unknown(engineId, null);
    }

    /** The engine cannot decide offline, with an engine-supplied reason. */
    public static RowSecurityClassification unknown(String engineId, String reason) {
        return new RowSecurityClassification(RowSecurityOutcome.UNKNOWN, engineId, Set.of(), reason);
    }

    /** No directive targets anything the query references. */
    public static RowSecurityClassification notApplicable(String engineId) {
        return new RowSecurityClassification(RowSecurityOutcome.NOT_APPLICABLE, engineId, Set.of(),
                null);
    }

    /** Predicates would be spliced in; the query runs filtered. */
    public static RowSecurityClassification applied(String engineId, Set<UUID> appliedPolicyIds) {
        return new RowSecurityClassification(RowSecurityOutcome.APPLIED, engineId, appliedPolicyIds,
                null);
    }

    /** A directive resolved to no values, so the submitter would see nothing. */
    public static RowSecurityClassification denyAll(String engineId, Set<UUID> appliedPolicyIds) {
        return new RowSecurityClassification(RowSecurityOutcome.DENY_ALL, engineId, appliedPolicyIds,
                null);
    }

    /** The shape cannot be provably filtered, so the query would be rejected instead of run. */
    public static RowSecurityClassification failClosed(String engineId, String reason) {
        return new RowSecurityClassification(RowSecurityOutcome.FAIL_CLOSED, engineId, Set.of(),
                reason);
    }
}
