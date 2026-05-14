package com.bablsoft.accessflow.bootstrap.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapExceptionTest {

    @Test
    void messageContainsAllErrors() {
        var ex = new BootstrapException(List.of("org: missing name", "admin: missing password"));

        assertThat(ex.getMessage())
                .contains("2 error(s)")
                .contains("org: missing name")
                .contains("admin: missing password");
    }

    @Test
    void reconcileErrorsReturnsImmutableCopy() {
        var input = new java.util.ArrayList<>(List.of("a", "b"));
        var ex = new BootstrapException(input);
        input.clear();

        assertThat(ex.reconcileErrors()).containsExactly("a", "b");
    }
}
