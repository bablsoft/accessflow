package com.bablsoft.accessflow.security.api;

/**
 * Marker on the Spring Security {@code Authentication} produced for an AccessFlow API-key request
 * (as opposed to an interactive JWT session). Lets other modules detect the request channel —
 * e.g. routing policies flag an API-key submission as CI/CD origin (AF-446) — without reaching into
 * {@code security.internal}. The concrete token stays internal; only this marker is exposed.
 */
public interface ApiKeyAuthentication {
}
