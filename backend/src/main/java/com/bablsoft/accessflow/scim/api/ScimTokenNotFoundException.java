package com.bablsoft.accessflow.scim.api;

import java.util.UUID;

public final class ScimTokenNotFoundException extends ScimException {

    public ScimTokenNotFoundException(UUID tokenId) {
        super("SCIM token not found: " + tokenId);
    }
}
