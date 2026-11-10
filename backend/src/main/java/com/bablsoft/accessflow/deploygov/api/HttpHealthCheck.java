package com.bablsoft.accessflow.deploygov.api;

/**
 * Passes when {@code url} answers {@code expectedStatus} and, when {@code bodyContains} is set,
 * the response body contains it.
 */
public record HttpHealthCheck(String name, String url, int expectedStatus, String bodyContains)
        implements VerificationCheck {

    @Override
    public VerificationCheckKind kind() {
        return VerificationCheckKind.HTTP_HEALTH;
    }
}
