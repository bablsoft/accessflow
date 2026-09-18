package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The human the current API-key request is acting <em>for</em> (#874), resolved by the
 * {@code X-AccessFlow-On-Behalf-Of} filter and kept in a request attribute — never on the
 * security principal. This is the <b>only</b> accessor; submission controllers and MCP tools read it
 * to stamp {@code on_behalf_of_user_id} and the audit contributor reads it for provenance. Nothing
 * on the authorization path does, and nothing here can widen a permission set.
 *
 * <p>Empty off the request thread, on a JWT session, and whenever the header was absent.
 */
public interface OnBehalfOfPrincipalService {

    Optional<UUID> current();
}
