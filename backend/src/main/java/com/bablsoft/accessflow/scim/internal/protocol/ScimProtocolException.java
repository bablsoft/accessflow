package com.bablsoft.accessflow.scim.internal.protocol;

/**
 * A SCIM-protocol-level failure (#621), rendered as the SCIM error envelope by
 * {@code ScimErrorHandler}. Detail strings are deliberately not localized — the consumer is an
 * IdP provisioning engine, and operators debug sync against fixed-language messages.
 */
public abstract class ScimProtocolException extends RuntimeException {

    private final int status;
    private final String scimType;

    protected ScimProtocolException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public int status() {
        return status;
    }

    /** RFC 7644 {@code scimType}, or null when the status alone carries the meaning. */
    public String scimType() {
        return scimType;
    }
}
