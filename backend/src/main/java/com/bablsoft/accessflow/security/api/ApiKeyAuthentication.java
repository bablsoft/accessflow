package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Marker on the Spring Security {@code Authentication} produced for an AccessFlow API-key request
 * (as opposed to an interactive JWT session). Lets other modules detect the request channel —
 * e.g. routing policies flag an API-key submission as CI/CD origin (AF-446) — without reaching into
 * {@code security.internal}. The concrete token stays internal; only this marker is exposed.
 *
 * <p>Since #869 the marker also names <em>which</em> key authenticated the request. The principal
 * ({@link JwtClaims}) is deliberately unchanged: the key id lives here, beside the permission
 * set, never inside it.
 */
public interface ApiKeyAuthentication {

    /** The {@code api_keys.id} of the key presented on this request. */
    UUID apiKeyId();

    /**
     * The calling application stored on the presented key (#938), or {@code null} when the key
     * names none. Trustworthy — unlike the {@code X-AccessFlow-Application} header.
     */
    default String applicationName() {
        return null;
    }
}
