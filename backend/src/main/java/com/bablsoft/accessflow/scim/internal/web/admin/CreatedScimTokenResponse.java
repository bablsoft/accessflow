package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.scim.api.IssuedScimToken;

/** {@code rawToken} appears here and nowhere else — it is not recoverable afterwards. */
record CreatedScimTokenResponse(ScimTokenResponse token, String rawToken) {

    static CreatedScimTokenResponse from(IssuedScimToken issued) {
        return new CreatedScimTokenResponse(ScimTokenResponse.from(issued.token()),
                issued.rawToken());
    }
}
