package com.bablsoft.accessflow.core.api;

/**
 * Raised when a masking-policy create/update request is structurally invalid — e.g. an unknown
 * reveal role, a reveal user/group that is not in the organization, or a strategy parameter that
 * is out of range. The {@code message} is a resolved, localized string supplied by the caller.
 */
public final class IllegalMaskingPolicyException extends MaskingPolicyException {

    public IllegalMaskingPolicyException(String message) {
        super(message);
    }
}
