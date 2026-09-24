package com.bablsoft.accessflow.core.api;

/**
 * Raised when a row-limit-policy create/update request is structurally invalid — a blank table, a
 * non-positive {@code max_rows}, an unknown {@code applies_to} role, or an {@code applies_to}
 * user/group outside the organization. The {@code message} is a resolved, localized string.
 */
public final class IllegalRowLimitPolicyException extends RowLimitPolicyException {

    public IllegalRowLimitPolicyException(String message) {
        super(message);
    }
}
