package com.bablsoft.accessflow.scim.internal.protocol;

public final class ScimInvalidPathException extends ScimProtocolException {

    public ScimInvalidPathException(String detail) {
        super(400, "invalidPath", detail);
    }
}
