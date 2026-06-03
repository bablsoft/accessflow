package com.bablsoft.accessflow.core.api;

/**
 * Raised when a row-security-policy create/update request is structurally invalid — e.g. a blank
 * table/column, an unknown {@code applies_to} role, an {@code applies_to} user/group not in the
 * organization, a variable expression outside the {@code user.*} namespace, or an operator/value
 * arity mismatch (a list variable with a scalar operator). The {@code message} is a resolved,
 * localized string supplied by the caller.
 */
public final class IllegalRowSecurityPolicyException extends RowSecurityPolicyException {

    public IllegalRowSecurityPolicyException(String message) {
        super(message);
    }
}
