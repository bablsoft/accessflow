package com.bablsoft.accessflow.scim.api;

import java.util.UUID;

/**
 * The authenticated identity of a SCIM request (#621): the organization is derived from the
 * bearer token — never from the request path or body.
 */
public record ScimPrincipal(UUID organizationId, UUID tokenId, String tokenName) {
}
