package com.bablsoft.accessflow.apigov.api;

/**
 * A connector variable failed validation — a bad name, a field required or forbidden by its kind, an
 * algorithm that does not match the kind, a malformed target, a duplicate name or target, a
 * reference to an unknown variable, or a dependency cycle. The message is resolved at the throw site
 * via {@code MessageSource} and surfaced verbatim as the 422 detail.
 *
 * <p>Messages must never embed a resolved value or a stored secret — only variable names.
 */
public class IllegalApiConnectorVariableException extends ApiGovException {

    public IllegalApiConnectorVariableException(String message) {
        super(message);
    }
}
