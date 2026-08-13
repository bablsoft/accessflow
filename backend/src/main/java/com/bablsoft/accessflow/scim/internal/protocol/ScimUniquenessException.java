package com.bablsoft.accessflow.scim.internal.protocol;

public final class ScimUniquenessException extends ScimProtocolException {

    public ScimUniquenessException(String detail) {
        super(409, "uniqueness", detail);
    }
}
