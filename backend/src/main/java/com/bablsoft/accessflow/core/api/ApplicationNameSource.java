package com.bablsoft.accessflow.core.api;

/**
 * Where a request's calling-application name came from (#938). {@link #API_KEY} is trustworthy —
 * the name is stored on the key and cannot be forged without it. {@link #HEADER} is the
 * caller-supplied {@code X-AccessFlow-Application} header and is entirely client-controlled, so it
 * must never be the sole basis of a permissive decision.
 */
public enum ApplicationNameSource {
    API_KEY,
    HEADER
}
