package com.bablsoft.accessflow.core.api;

/**
 * Raised when a data-budget create/update request is structurally invalid — no limit, a limit or
 * window out of range, an unknown {@code applies_to} role, or a user/group outside the
 * organization. The {@code message} is a resolved, localized string.
 */
public final class IllegalDataBudgetException extends DataBudgetException {

    public IllegalDataBudgetException(String message) {
        super(message);
    }
}
