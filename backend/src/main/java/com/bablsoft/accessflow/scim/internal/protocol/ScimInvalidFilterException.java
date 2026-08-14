package com.bablsoft.accessflow.scim.internal.protocol;

public final class ScimInvalidFilterException extends ScimProtocolException {

    public ScimInvalidFilterException(String detail) {
        super(400, "invalidFilter", detail);
    }
}
