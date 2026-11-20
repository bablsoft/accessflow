package com.bablsoft.accessflow.core.api;

/**
 * Why a connection to a customer database could not be opened, as far as AccessFlow can tell
 * without showing the driver's own error text.
 */
public enum ConnectionFailureReason {
    /** The TLS handshake failed, or the server and the configured SSL mode disagree. */
    SSL,
    /** The server refused the credentials. */
    AUTHENTICATION,
    /** The host name did not resolve, or nothing accepted the connection. */
    HOST_UNREACHABLE,
    /** The server did not answer in time. */
    TIMEOUT,
    /** The server answered, but has no database of that name. */
    DATABASE_NOT_FOUND,
    /** None of the above could be established. */
    UNKNOWN
}
