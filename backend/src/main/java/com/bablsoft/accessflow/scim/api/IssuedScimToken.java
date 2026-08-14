package com.bablsoft.accessflow.scim.api;

/** Result of creating a SCIM token (#621): {@code rawToken} is returned exactly once. */
public record IssuedScimToken(ScimTokenView token, String rawToken) {
}
