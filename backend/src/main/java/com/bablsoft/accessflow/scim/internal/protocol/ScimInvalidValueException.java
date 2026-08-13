package com.bablsoft.accessflow.scim.internal.protocol;

public final class ScimInvalidValueException extends ScimProtocolException {

    public ScimInvalidValueException(String detail) {
        super(400, "invalidValue", detail);
    }
}
