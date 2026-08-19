package com.bablsoft.accessflow.core.api;

/**
 * Raised when an export-policy create/update request is structurally invalid — e.g. a missing
 * mode, a {@code row_cap} absent for {@code ROW_CAP} (or present for any other mode),
 * {@code deny_classifications} on a non-deny mode, an unknown {@code applies_to} role, or an
 * {@code applies_to} user/group not in the organization. The {@code message} is a resolved,
 * localized string supplied by the caller.
 */
public final class IllegalExportPolicyException extends ExportPolicyException {

    public IllegalExportPolicyException(String message) {
        super(message);
    }
}
