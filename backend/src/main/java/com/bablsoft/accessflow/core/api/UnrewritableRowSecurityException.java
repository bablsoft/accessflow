package com.bablsoft.accessflow.core.api;

/**
 * Raised when a query references a row-security-policied table in a structural position the proxy
 * cannot provably rewrite — e.g. inside a {@code UNION}, a CTE, a sub-select the rewriter does not
 * descend into, an {@code INSERT … SELECT}, or an {@code UPDATE … FROM} / {@code DELETE … USING}
 * join onto another policied table. The query is rejected (HTTP 422) rather than executed
 * unfiltered, so authorised-row enforcement can never be silently bypassed. The {@code message} is
 * a resolved, localized string supplied at the throw site.
 */
public final class UnrewritableRowSecurityException extends RuntimeException {

    public UnrewritableRowSecurityException(String message) {
        super(message);
    }
}
